(ns llm-planner.cli
  (:require
   [cli-matic.core :as cli-matic]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.pprint :as pp]
   [llm-planner.ast :as ast]
   [llm-planner.config :as config]
   [llm-planner.db :as db]
   [llm-planner.indexer :as indexer]))


(defn migrate-db [])

(def ^:private DB-CONFIG
  {:command "db"
   :description "Database operations"
   :subcommands
   [{:command "show"
     :description "Initialize database"
     :runs (comp
            pp/pprint
            db/file-sqlite-database
            config/load-config)}

    {:command "migrate"
     :description "Run migrations"
     :runs (comp
            db/migrate
            db/migration-config
            db/file-sqlite-database
            config/load-config)}]})

(def ^:private CONFIG-CONFIG
  {:command "config"
   :description "Returns the current configuration"
   :subcommands [{:command "show"
                  :runs (comp pp/pprint config/load-config)}]})

(defn summarize-file
  "Summarize a Clojure file showing namespace, requires, and top-level vars.
  
  Analyzes a Clojure file and returns JSON summary with:
  - :namespace - namespace name
  - :namespace-doc - namespace docstring (or null)
  - :requires - array of require specs
  - :defs - array of {name, docstring, type} for def forms
  - :defns - array of {name, docstring, type} for defn forms
  
  Useful for quickly understanding a file's API without reading full source.
  Docstrings are null if not present.
  
  Examples:
    # Summarize a file
    llm-planner ast summary -f src/llm_planner/ast.clj
    
    # Extract function names with docstrings
    llm-planner ast summary -f src/core.clj | jq '.defns[] | select(.docstring) | .name'
    
    # Count functions
    llm-planner ast summary -f src/core.clj | jq '.defns | length'
  
  Args:
  - file: Path to Clojure file (relative to CWD or absolute)
  
  Exit codes:
  - 0: Success
  - 1: File not found or parse error"
  [{:keys [file]}]
  (try
    (let [file-path (io/file file)
          absolute-path (.getAbsolutePath file-path)]
      (if (.exists file-path)
        (let [code (slurp absolute-path)
              ns-form (ast/find-namespace code)
              defns (ast/find-defns code)
              defs (ast/find-defs code)
              summary {:namespace (:name ns-form)
                       :namespace-doc (:docstring ns-form)
                       :requires (:requires ns-form)
                       :defns (mapv #(select-keys % [:name :docstring]) defns)
                       :defs (mapv #(select-keys % [:name :docstring]) defs)}]
          (println (json/write-str summary :indent true)))
        (do
          (println "Error: File not found:" absolute-path)
          (System/exit 1))))
    (catch Exception e
      (println "Error analyzing file:" (.getMessage e))
      (when-let [data (ex-data e)]
        (pp/pprint data))
      (System/exit 1))))

(defn parse-file-ast
  "Parse a Clojure file and output its AST as JSON.
  
  Uses rewrite-clj to parse Clojure code into an Abstract Syntax Tree (AST)
  that preserves all formatting, whitespace, and comments. Output is JSON
  suitable for querying with jq or storing in databases.
  
  AST Structure:
  - :tag - Node type (e.g., :list, :token, :vector, :map, :comment)
  - :string - Source code representation of the node
  - :children - Nested child nodes (for container types)
  
  Examples:
    # Pretty-print AST
    llm-planner ast parse -f src/my_app/core.clj
    
    # Compact JSON (one line)
    llm-planner ast parse -f src/core.clj --compact
    
    # Extract function names with jq
    llm-planner ast parse -f src/core.clj | jq '[.children[] | select(.tag == \"list\") | select(.children[0].string == \"defn\") | .children[2].string]'
    
    # Count top-level forms
    llm-planner ast parse -f src/core.clj | jq '.children | length'
  
  Args:
  - file: Path to Clojure file (relative to CWD or absolute)
  - compact: If true, output single-line JSON; otherwise pretty-printed
  
  Exit codes:
  - 0: Success
  - 1: File not found or parse error"
  [{:keys [file compact]}]
  (try
    (let [file-path (io/file file)
          absolute-path (.getAbsolutePath file-path)]
      (if (.exists file-path)
        (let [parsed-ast (ast/parse-file absolute-path)
              json-str (ast/ast->json parsed-ast)
              parsed-json (json/read-str json-str)]
          (if compact
            (println (json/write-str parsed-json))
            (println (json/write-str parsed-json :indent true))))
        (do
          (println "Error: File not found:" absolute-path)
          (System/exit 1))))
    (catch Exception e
      (println "Error parsing file:" (.getMessage e))
      (when-let [data (ex-data e)]
        (pp/pprint data))
      (System/exit 1))))

(def ^:private AST-CONFIG
  {:command "ast"
   :description "Parse and analyze Clojure code using rewrite-clj. Outputs AST as JSON preserving formatting, comments, and whitespace. Use with jq for advanced queries."
   :subcommands
   [{:command "parse"
     :description "Parse Clojure file to JSON AST. Preserves all formatting/comments. Pipe to jq for queries (e.g., extract defns, count forms, analyze structure)."
     :opts [{:option "file"
             :short "f"
             :as "Clojure file path (relative to CWD or absolute). Supports .clj, .cljs, .cljc, .edn files."
             :type :string
             :required true}
            {:option "compact"
             :short "c"
             :as "Output single-line JSON (no indentation). Useful for piping to tools or storing in databases."
             :type :with-flag
             :default false}]
     :runs parse-file-ast}

    {:command "summary"
     :description "Summarize Clojure file as JSON: namespace, requires, defs/defns with docstrings. Fast API overview without reading source."
     :opts [{:option "file"
             :short "f"
             :as "Clojure file path (relative to CWD or absolute). Supports .clj, .cljs, .cljc files."
             :type :string
             :required true}]
     :runs summarize-file}]})

(defn run-index
  "Index all Clojure files in the project.
   
   Discovers files based on source_paths and file_patterns in config,
   parses each file, and stores AST in database."
  [opts]
  (let [config (config/load-config opts)
        conn (db/file-sqlite-database config)]
    (try
      (indexer/build-index conn config)
      (finally
        (.close conn)))))

(def ^:private INDEX-CONFIG
  {:command "index"
   :description "Index all Clojure files in the project. Parses files and stores AST in database for later analysis."
   :runs run-index})


(def ^:private COMMAND-CONFIG
  {:command "llm-planner"
   :version "0.0.1"
   :opts        [{:as      "config-path"
                  :default ".llm-planner/config.yml"
                  :option  "config-path"
                  :type    :string}]
   :description "A tool for working with LLM tools"
   :subcommands [DB-CONFIG CONFIG-CONFIG AST-CONFIG INDEX-CONFIG]})

(defn main-fn
  [args]
  (cli-matic/run-cmd args COMMAND-CONFIG))
