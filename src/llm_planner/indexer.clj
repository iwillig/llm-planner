(ns llm-planner.indexer
  "Functions for indexing Clojure source files.
   
   This namespace provides functionality to:
   - Discover Clojure files in source directories
   - Parse files and extract AST
   - Store parsed files in database
   - Track indexing progress and errors"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [honey.sql :as sql]
   [llm-planner.ast :as ast]
   [next.jdbc :as jdbc])
  (:import
   [java.nio.file FileSystems
    Paths]))

;; ============================================================================
;; File Discovery
;; ============================================================================

(defn- path-matcher
  "Create a PathMatcher for a glob pattern.
   
   Example:
     (path-matcher \"**/*.clj\")
     => #<PathMatcher ...>"
  [pattern]
  (.getPathMatcher (FileSystems/getDefault)
                   (str "glob:" pattern)))

(defn- matches-pattern?
  "Check if a file path matches any of the given glob patterns."
  [file-path patterns]
  (let [matchers (map path-matcher patterns)
        path (Paths/get file-path (into-array String []))]
    (some #(.matches % path) matchers)))

(defn find-clojure-files
  "Find all files in source paths matching file patterns.
   
   Args:
     source-paths - Vector of directory paths (e.g., [\"src\" \"test\"])
     file-patterns - Vector of glob patterns (e.g., [\"**/*.clj\"])
     
   Returns:
     Sequence of relative file paths
     
   Example:
     (find-clojure-files [\"src\"] [\"**/*.clj\"])
     => (\"src/llm_planner/core.clj\" \"src/llm_planner/ast.clj\" ...)"
  [source-paths file-patterns]
  (let [cwd (System/getProperty "user.dir")]
    (mapcat
     (fn [source-path]
       (let [source-dir (io/file cwd source-path)]
         (when (.exists source-dir)
           (->> (file-seq source-dir)
                (filter #(.isFile %))
                (map #(str/replace-first (.getPath %) (str cwd "/") ""))
                (filter #(matches-pattern? % file-patterns))))))
     source-paths)))

;; ============================================================================
;; Database Operations
;; ============================================================================

(defn ensure-project
  "Ensure project exists in database, return project ID.
   
   If project with given name exists, returns its ID.
   Otherwise, creates new project and returns new ID.
   
   Args:
     db - Database connection
     project-config - Map with :name and :path keys
     
   Returns:
     Project ID (integer)"
  [db {:keys [name]}]
  (if-let [existing (jdbc/execute-one!
                     db
                     (sql/format
                      {:select [:id]
                       :from [:project]
                       :where [:= :name name]}))]
    (:project/id existing)
    (let [cwd (System/getProperty "user.dir")
          result (jdbc/execute-one!
                  db
                  (sql/format
                   {:insert-into :project
                    :values [{:name name
                              :description (str "Clojure project: " name)
                              :path cwd}]
                    :returning [:id]}))]
      (:project/id result))))

(defn index-file
  "Parse and store a single file's AST in database.
   
   Args:
     db - Database connection
     project-id - Project ID
     file-path - Relative path to file
     
   Returns:
     Map with :success, :file-path, and optionally :error
     
   Example:
     (index-file db 1 \"src/core.clj\")
     => {:success true :file-path \"src/core.clj\" :content-id 1}
     
     (index-file db 1 \"src/broken.clj\")
     => {:success false :file-path \"src/broken.clj\" 
         :error \"EOF while reading\"}"
  [db project-id file-path]
  (try
    (let [content (slurp file-path)
          ast (ast/parse-string content)]
      (if (ast/parse-error? ast)
        {:success false
         :file-path file-path
         :error (:message ast)}
        (let [;; Check if file already exists
              existing-file (jdbc/execute-one!
                             db
                             (sql/format
                              {:select [:id]
                               :from [:file]
                               :where [:and
                                       [:= :project_id project-id]
                                       [:= :path file-path]]}))
              file-id (if existing-file
                        (:file/id existing-file)
                        (:file/id
                         (jdbc/execute-one!
                          db
                          (sql/format
                           {:insert-into :file
                            :values [{:project_id project-id
                                      :path file-path}]
                            :returning [:id]}))))
              content-id (ast/store-file-content db file-id content)]
          {:success true
           :file-path file-path
           :file-id file-id
           :content-id content-id})))
    (catch Exception e
      {:success false
       :file-path file-path
       :error (.getMessage e)})))

;; ============================================================================
;; Indexing
;; ============================================================================

(defn index-project
  "Index all files in project.
   
   Args:
     db - Database connection
     config - Full configuration map with :project key
     
   Returns:
     Map with indexing results:
       :total - Total files found
       :parsed - Number successfully parsed
       :failed - Number that failed
       :errors - Vector of error maps
       
   Example:
     (index-project db config)
     => {:total 15 :parsed 14 :failed 1 
         :errors [{:file-path \"src/broken.clj\" :error \"...\"}]}"
  [db config]
  (let [{:keys [name source_paths file_patterns]} (:project config)
        _ (println "Indexing project:" name)
        project-id (ensure-project db {:name name})
        files (find-clojure-files source_paths file_patterns)
        file-count (count files)]
    (println "Found" file-count "Clojure files")
    (println)
    
    (let [results (doall
                   (map-indexed
                    (fn [idx file-path]
                      (print (str "  [" (inc idx) "/" file-count "] "
                                  "Parsing " file-path "... "))
                      (flush)
                      (let [result (index-file db project-id file-path)]
                        (if (:success result)
                          (println "✓")
                          (println "✗"))
                        result))
                    files))
          successes (filter :success results)
          failures (filter #(not (:success %)) results)]
      (println)
      (println "Summary:")
      (println "  Total files:" file-count)
      (println "  Parsed:" (count successes))
      (println "  Failed:" (count failures))
      (when (seq failures)
        (println)
        (println "Errors:")
        (doseq [{:keys [file-path error]} failures]
          (println "  -" file-path ":" error)))
      (println)
      (if (empty? failures)
        (println "Index complete!")
        (println "Index complete with errors."))
      
      {:total file-count
       :parsed (count successes)
       :failed (count failures)
       :errors (map #(select-keys % [:file-path :error]) failures)})))

(defn build-index
  "Main entry point for building project index.
   
   Reads configuration, discovers files, and indexes them.
   
   Args:
     db - Database connection
     config - Full configuration map
     
   Returns:
     Indexing results map"
  [db config]
  (if (:project config)
    (index-project db config)
    (do
      (println "Error: No project configuration found in config file")
      (println "Please add a 'project' section to .llm-planner/config.yml")
      {:total 0 :parsed 0 :failed 0 :errors []})))
