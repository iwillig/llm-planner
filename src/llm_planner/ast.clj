(ns llm-planner.ast
  "Functions for parsing Clojure code and storing AST in SQLite.
   
   This namespace uses a compact vector-based AST format for space efficiency.
   The format achieves ~80-90% size reduction compared to verbose serialization.
   
   Core API:
   - parse-string: Parse Clojure code to AST
   - find-defns, find-defs, find-namespace: Find forms in AST
   - store-file-content!, get-file-content-ast: Database operations
   
   AST Format:
   - Vectors with tag-first structure: [tag ...content]
   - Token nodes: [:tok value]
   - Collection nodes: [:list ...], [:vec ...], [:map ...], [:set ...]
   - Example: (defn add [x y] (+ x y))
     => [:forms [:list [:tok 'defn] [:tok 'add]
                       [:vec [:tok 'x] [:tok 'y]]
                       [:list [:tok '+] [:tok 'x] [:tok 'y]]]]"
  (:require
   [clojure.data.json :as json]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [honey.sql :as sql]
   [llm-planner.ast.compact :as compact]
   [next.jdbc :as jdbc]))

;; ============================================================================
;; Core Parsing
;; ============================================================================

(defn parse-error?
  "Check if parse result is an error."
  [result]
  (and (map? result) (:error result)))

(defn parse-string
  "Parse a Clojure string and return AST.
   Returns AST vector or map with :error key if parsing fails.
   
   Examples:
     (parse-string \"(+ 1 2)\")
     ;;=> [:forms [:list [:tok '+] [:tok 1] [:tok 2]]]
     
     (parse-string \"(defn broken\")
     ;;=> {:error true, :message \"...\", :input \"(defn broken\"}"
  [code-str]
  (compact/compact-ast code-str))

(defn parse-file
  "Parse a Clojure file and return AST.
   Returns AST vector or map with :error key if parsing fails."
  [file-path]
  (try
    (-> file-path slurp parse-string)
    (catch Exception e
      {:error true
       :message (.getMessage e)
       :file-path file-path})))

(defn reconstruct
  "Reconstruct Clojure source code from AST.
   
   Example:
     (reconstruct (parse-string \"(+ 1 2)\"))
     ;;=> \"(+ 1 2)\""
  [ast]
  (compact/reconstruct ast))

;; ============================================================================
;; JSON Serialization (for database storage)
;; ============================================================================

(defn ast->json
  "Convert AST to JSON string for storage.
   Symbols and keywords are converted to strings with type markers."
  [ast]
  (let [prepared (walk/postwalk
                  (fn [x]
                    (cond
                      (symbol? x) {:__type "symbol" :__value (str x)}
                      (keyword? x) {:__type "keyword" :__value (name x)}
                      :else x))
                  ast)]
    (json/write-str prepared)))

(defn json->ast
  "Parse JSON string back to AST.
   Converts type-marked values back to symbols and keywords."
  [json-str]
  (let [parsed (json/read-str json-str :key-fn keyword)]
    (walk/postwalk
     (fn [x]
       (cond
         ;; Restore symbols
         (and (map? x) (= (:__type x) "symbol"))
         (symbol (:__value x))

         ;; Restore keywords  
         (and (map? x) (= (:__type x) "keyword"))
         (keyword (:__value x))

         :else x))
     parsed)))

;; ============================================================================
;; Finding Forms
;; ============================================================================

(defn find-defns
  "Find all defn forms in source code or AST.
   
   Args:
     source-or-ast - Either string of Clojure code or AST vector
   
   Returns:
     Vector of maps: [{:name, :docstring, :node, :source}, ...]
   
   Example:
     (find-defns \"(defn foo [x] x) (defn bar [y] y)\")
     ;;=> [{:name 'foo, :docstring nil, :node [...], :source \"(defn foo [x] x)\"}
     ;;    {:name 'bar, :docstring nil, :node [...], :source \"(defn bar [y] y)\"}]"
  [source-or-ast]
  (let [ast (if (string? source-or-ast)
              (parse-string source-or-ast)
              source-or-ast)]
    (when-not (parse-error? ast)
      (let [defn-nodes (compact/find-defns ast)]
        (mapv (fn [node]
                {:name (compact/extract-fn-name node)
                 :docstring (compact/extract-docstring node)
                 :node node
                 :source (compact/reconstruct node)})
              defn-nodes)))))

(defn find-defs
  "Find all def forms in source code or AST.
   See find-defns for API details."
  [source-or-ast]
  (let [ast (if (string? source-or-ast)
              (parse-string source-or-ast)
              source-or-ast)]
    (when-not (parse-error? ast)
      (let [def-nodes (compact/find-defs ast)]
        (mapv (fn [node]
                {:name (compact/extract-fn-name node)
                 :docstring (compact/extract-docstring node)
                 :node node
                 :source (compact/reconstruct node)})
              def-nodes)))))

(defn find-namespace
  "Find the ns form in source code or AST.
   
   Returns:
     Map: {:name, :docstring, :requires, :node, :source}
     
   Example:
     (find-namespace \"(ns my.app (:require [clojure.string :as str]))\")
     ;;=> {:name 'my.app, :docstring nil, :requires [[clojure.string :as str]], ...}"
  [source-or-ast]
  (let [ast (if (string? source-or-ast)
              (parse-string source-or-ast)
              source-or-ast)]
    (when-not (parse-error? ast)
      (when-let [ns-node (compact/find-ns-form ast)]
        {:name (compact/extract-namespace-name ns-node)
         :docstring (compact/extract-namespace-docstring ns-node)
         :requires (compact/extract-requires ns-node)
         :node ns-node
         :source (compact/reconstruct ns-node)}))))

;; ============================================================================
;; Comparison Functions
;; ============================================================================

(defn compare-forms
  "Compare forms between old and new source code.
   
   Args:
     old-source - String of old code or AST
     new-source - String of new code or AST
     form-finder-fn - Function to find forms (e.g., find-defns, find-defs)
   
   Returns sequence of change maps:
     {:change-type \"update\"|\"addition\"|\"removal\"
      :name <symbol>
      :old-source <string> (for update/removal)
      :new-source <string> (for update/addition)}
   
   Example:
     (compare-forms \"(defn foo [x] (+ x 1))\"
                    \"(defn foo [x] (+ x 2)) (defn bar [y] y)\"
                    find-defns)
     ;;=> ({:change-type \"update\", :name 'foo, :old-source \"...\", :new-source \"...\"}
     ;;    {:change-type \"addition\", :name 'bar, :new-source \"...\"})"
  [old-source new-source form-finder-fn]
  (let [old-forms (into {} (map (fn [f] [(:name f) f])
                                (form-finder-fn old-source)))
        new-forms (into {} (map (fn [f] [(:name f) f])
                                (form-finder-fn new-source)))
        all-names (into #{} (concat (keys old-forms) (keys new-forms)))]
    (keep
     (fn [name]
       (let [old (get old-forms name)
             new (get new-forms name)
             old-src (:source old)
             new-src (:source new)]
         (cond
           (and old new (not= old-src new-src))
           {:change-type "update"
            :name name
            :old-source old-src
            :new-source new-src}

           (and (not old) new)
           {:change-type "addition"
            :name name
            :new-source new-src}

           (and old (not new))
           {:change-type "removal"
            :name name
            :old-source old-src})))
     all-names)))

(defn compare-defns
  "Compare defn forms between old and new source code.
   See compare-forms for details on return value."
  [old-source new-source]
  (compare-forms old-source new-source find-defns))

(defn compare-defs
  "Compare def forms between old and new source code.
   See compare-forms for details on return value."
  [old-source new-source]
  (compare-forms old-source new-source find-defs))

;; ============================================================================
;; Database Operations
;; ============================================================================

(defn store-file-content!
  "Store file content with AST in database.
   Returns the inserted row's ID, or nil if parsing fails.
   
   Example:
     (store-file-content! db 1 \"(defn hello [] \\\"world\\\")\")"
  [db file-id content]
  (let [ast (parse-string content)]
    (if (parse-error? ast)
      nil
      (let [ast-json (ast->json ast)
            result (jdbc/execute-one!
                    db
                    (sql/format
                     {:insert-into :file_content
                      :values [{:file_id file-id
                                :content content
                                :compact_ast ast-json}]
                      :returning [:id]}))]
        (:file_content/id result)))))

(defn get-file-content-ast
  "Retrieve AST for a file content record.
   Returns nil if not found."
  [db content-id]
  (when-let [row (jdbc/execute-one!
                  db
                  (sql/format
                   {:select [:compact_ast]
                    :from [:file_content]
                    :where [:= :id content-id]}))]
    (json->ast (:file_content/compact_ast row))))

(defn store-form-change!
  "Store a specific form change in file_change_ast table.
   
   Args:
     db - Database connection
     file-change-id - ID of the file change record
     form-data - Map with :change-type, :name, and :new-source/:old-source"
  [db file-change-id form-data]
  (jdbc/execute-one!
   db
   (sql/format
    {:insert-into :file_change_ast
     :values [{:file_change_id file-change-id
               :node_path (str "defn[" (:name form-data) "]")
               :node_tag "defn"
               :node_string (or (:new-source form-data) (:old-source form-data))
               :node_compact_ast (ast->json form-data)
               :change_type (:change-type form-data)}]
     :returning [:id]})))

(defn store-defn-changes!
  "Store all defn changes between old and new content.
   Returns sequence of changes stored."
  [db file-change-id old-content new-content]
  (let [changes (compare-defns old-content new-content)]
    (doseq [change changes]
      (store-form-change! db file-change-id change))
    changes))

(defn query-forms-by-type
  "Query all stored forms of a specific type from file_change_ast."
  [db form-type]
  (jdbc/execute!
   db
   (sql/format
    {:select [:id :node_path :node_tag :node_string :change_type]
     :from [:file_change_ast]
     :where [:= :node_tag form-type]})))

;; ============================================================================
;; Examples
;; ============================================================================

(comment
  ;; Basic parsing
  (def ast (parse-string "(defn greet [name]
                            \"Greets a person\"
                            (str \"Hello, \" name))"))

  ;; Find all defns
  (find-defns ast)
  ;; => [{:name 'greet
  ;;      :docstring "Greets a person"
  ;;      :node [...]
  ;;      :source "(defn greet ...)"}]

  ;; Find namespace
  (find-namespace "(ns my.app
                     (:require [clojure.string :as str]))
                   (defn test [])")
  ;; => {:name 'my.app
  ;;     :docstring nil
  ;;     :requires [[clojure.string :as str]]
  ;;     :node [...]
  ;;     :source "(ns my.app ...)"}

  ;; Compare two versions
  (compare-defns "(defn foo [x] (+ x 1))"
                 "(defn foo [x] (+ x 2)) (defn bar [y] y)")
  ;; => ({:change-type "update", :name 'foo, :old-source "...", :new-source "..."}
  ;;     {:change-type "addition", :name 'bar, :new-source "..."})

  ;; Database operations
  (require '[llm-planner.db :as db])
  (def conn (db/memory-sqlite-database))
  (db/migrate (db/migration-config conn))

  ;; Store and retrieve
  (def content-id (store-file-content! conn 1 "(defn hello [] \"world\")"))
  (get-file-content-ast conn content-id))
