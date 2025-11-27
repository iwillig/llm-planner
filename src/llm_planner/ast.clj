(ns llm-planner.ast
  "Functions for parsing Clojure code with rewrite-clj and storing in SQLite as JSON"
  (:require
   [clojure.data.json :as json]
   [clojure.walk :as walk]
   [next.jdbc :as jdbc]
   [honey.sql :as sql]
   [rewrite-clj.parser :as p]
   [rewrite-clj.node :as n]))

;; ============================================================================
;; AST Serialization/Deserialization
;; ============================================================================

(defn serialize-node-for-db
  "Convert rewrite-clj node to JSON-ready map for database storage.
   Preserves tag and string representation, recursively processes children.
   Does NOT include s-expression values to avoid serialization issues."
  [node]
  (when node
    (let [base {:tag (keyword (n/tag node))
                :string (n/string node)}]
      (if (n/inner? node)
        (assoc base :children (mapv serialize-node-for-db (n/children node)))
        base))))

(defn deserialize-ast-from-db
  "Convert JSON string from database back to AST map with proper keyword types.
   Ensures :tag values are keywords, not strings."
  [json-str]
  (let [parsed (json/read-str json-str)]
    (walk/postwalk
     (fn [x]
       (if (map? x)
         (into {}
               (map (fn [[k v]]
                      [(keyword k)
                       (if (and (= k "tag") (string? v))
                         (keyword v)
                         v)])
                    x))
         x))
     parsed)))

(defn ast->json
  "Convert AST map to JSON string for storage"
  [ast-map]
  (json/write-str ast-map))

(defn json->ast
  "Parse JSON string back to AST map"
  [json-str]
  (deserialize-ast-from-db json-str))

;; ============================================================================
;; AST Parsing
;; ============================================================================

(defn parse-clojure-file
  "Parse a Clojure file and return serialized AST"
  [file-path]
  (-> file-path
      slurp
      p/parse-string-all
      serialize-node-for-db))

(defn parse-clojure-string
  "Parse a Clojure string and return serialized AST"
  [code-str]
  (-> code-str
      p/parse-string-all
      serialize-node-for-db))

;; ============================================================================
;; AST Analysis
;; ============================================================================

(defn find-forms-by-type
  "Find all forms of a specific type (e.g., 'defn', 'def', 'ns') in AST.
   Returns vector of maps with :name, :full-form, and :children"
  [ast-map form-type]
  (let [results (atom [])]
    (walk/postwalk
     (fn [node]
       (when (and (map? node)
                  (= :list (:tag node)))
         (when-let [children (:children node)]
           (when-let [first-child (first children)]
             (when (and (= :token (:tag first-child))
                        (= form-type (:string first-child)))
               ;; For defn/defmacro, name is 3rd element (after defn, whitespace)
               ;; For ns, name is 3rd element as well
               (let [name-token (nth children 2 nil)]
                 (swap! results conj
                        {:name (:string name-token)
                         :full-form (:string node)
                         :children children}))))))
       node)
     ast-map)
    @results))

(defn find-defns
  "Find all defn forms in AST"
  [ast-map]
  (find-forms-by-type ast-map "defn"))

(defn find-defs
  "Find all def forms in AST"
  [ast-map]
  (find-forms-by-type ast-map "def"))

(defn find-namespace-form
  "Find the ns form in AST"
  [ast-map]
  (first (find-forms-by-type ast-map "ns")))

(defn extract-top-level-forms
  "Extract all top-level forms from AST.
   Returns vector of maps with :form-type, :full-form, and :tag"
  [ast-map]
  (->> (:children ast-map)
       (filter #(= :list (:tag %)))
       (map (fn [form]
              (let [first-child (first (:children form))
                    form-type (:string first-child)]
                {:form-type form-type
                 :full-form (:string form)
                 :tag (:tag form)})))))

;; ============================================================================
;; AST Comparison
;; ============================================================================

(defn compare-forms
  "Compare forms between two ASTs by name.
   Returns sequence of change maps with :change-type, :name, :old-form, :new-form"
  [old-ast new-ast form-type]
  (let [old-forms (into {} (map (fn [f] [(:name f) f])
                                (find-forms-by-type old-ast form-type)))
        new-forms (into {} (map (fn [f] [(:name f) f])
                                (find-forms-by-type new-ast form-type)))
        all-names (into #{} (concat (keys old-forms) (keys new-forms)))]
    (keep
     (fn [name]
       (let [old (get old-forms name)
             new (get new-forms name)]
         (cond
           (and old new (not= (:full-form old) (:full-form new)))
           {:change-type "update"
            :name name
            :old-form (:full-form old)
            :new-form (:full-form new)}

           (and (not old) new)
           {:change-type "addition"
            :name name
            :new-form (:full-form new)}

           (and old (not new))
           {:change-type "removal"
            :name name
            :old-form (:full-form old)})))
     all-names)))

(defn compare-defns
  "Compare defn forms between two ASTs"
  [old-ast new-ast]
  (compare-forms old-ast new-ast "defn"))

;; ============================================================================
;; Database Operations
;; ============================================================================

(defn store-file-content!
  "Store file content with parsed AST in database.
   Returns the inserted row's ID"
  [db file-id content]
  (let [ast (parse-clojure-string content)
        ast-json (ast->json ast)
        result (jdbc/execute-one!
                db
                (sql/format
                 {:insert-into :file_content
                  :values [{:file_id file-id
                            :content content
                            :ast_json ast-json}]
                  :returning [:id]}))]
    (:file_content/id result)))

(defn get-file-content-ast
  "Retrieve parsed AST for a file content record"
  [db content-id]
  (when-let [row (jdbc/execute-one!
                  db
                  (sql/format
                   {:select [:ast_json]
                    :from [:file_content]
                    :where [:= :id content-id]}))]
    (json->ast (:file_content/ast_json row))))

(defn store-form-change!
  "Store a specific form change (defn, def, etc.) in file_change_ast table"
  [db file-change-id form-data]
  (jdbc/execute-one!
   db
   (sql/format
    {:insert-into :file_change_ast
     :values [{:file_change_id file-change-id
               :node_path (str "defn[" (:name form-data) "]")
               :node_tag "defn"
               :node_string (or (:new-form form-data) (:old-form form-data))
               :node_ast_json (ast->json form-data)
               :change_type (:change-type form-data)}]
     :returning [:id]})))

(defn store-defn-changes!
  "Store all defn changes between old and new content"
  [db file-change-id old-content new-content]
  (let [old-ast (parse-clojure-string old-content)
        new-ast (parse-clojure-string new-content)
        changes (compare-defns old-ast new-ast)]
    (doseq [change changes]
      (store-form-change! db file-change-id change))
    changes))

(defn query-forms-by-type
  "Query all stored forms of a specific type from file_change_ast"
  [db form-type]
  (jdbc/execute!
   db
   (sql/format
    {:select [:id :node_path :node_tag :node_string :change_type]
     :from [:file_change_ast]
     :where [:= :node_tag form-type]})))

;; ============================================================================
;; Utility Functions
;; ============================================================================

(defn reconstruct-source-from-ast
  "Reconstruct source code from AST (preserves all whitespace and comments)"
  [ast-map]
  (:string ast-map))

(comment
  ;; Example usage:

  ;; Parse a string
  (def ast (parse-clojure-string "(defn hello [x] (println x))"))

  ;; Convert to JSON
  (def json-str (ast->json ast))

  ;; Parse back from JSON
  (def restored (json->ast json-str))

  ;; Find all defns
  (find-defns ast)

  ;; Compare two versions
  (def old-code "(defn foo [x] (+ x 1))")
  (def new-code "(defn foo [x] (+ x 2))\n(defn bar [y] (* y 2))")
  (compare-defns
   (parse-clojure-string old-code)
   (parse-clojure-string new-code))

  ;; Database operations (requires DB connection)
  (require '[llm-planner.db :as db])
  (def conn (db/memory-sqlite-database))

  ;; Create tables (would need proper migration)
  (jdbc/execute! conn
                 ["CREATE TABLE file_content (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    file_id INTEGER NOT NULL,
                    content TEXT NOT NULL,
                    ast_json TEXT,
                    parsed_at TEXT NOT NULL DEFAULT (datetime('now')),
                    created_at TEXT NOT NULL DEFAULT (datetime('now')))"])

  ;; Store content
  (store-file-content! conn 1 "(defn hello [] \"world\")")

  ;; Retrieve AST
  (get-file-content-ast conn 1)
  )
