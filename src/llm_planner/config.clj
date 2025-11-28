(ns llm-planner.config
  (:require
   [clj-yaml.core :as yaml]
   [malli.core :as m]))

(def default-path ".llm-planner/config.yml")

(def project-config-spec
  [:map
   [:name :string]
   [:type [:enum "clojure"]]
   [:source_paths [:sequential :string]]
   [:file_patterns [:sequential :string]]])

(def config-spec
  [:map
   [:db_path :string]
   [:project {:optional true} project-config-spec]])

(defn load-config
  "Load configuration from YAML file.
   
   Returns map with:
     :db_path - Path to SQLite database
     :project - (optional) Project configuration map with:
       :name - Project name
       :type - Project type (currently only 'clojure' supported)
       :source_paths - Vector of source directory paths
       :file_patterns - Vector of glob patterns for files to index"
  ([]
   (load-config {:config-path default-path}))
  ([{:keys [config-path]}]
   (let [config-content (slurp config-path)
         config (yaml/parse-string config-content)
         ;; Set defaults for optional project settings and convert sequences to vectors
         config-with-defaults
         (if-let [project (:project config)]
           (assoc config :project
                  (-> (merge {:source_paths ["src"]
                              :file_patterns ["**/*.clj" "**/*.cljs" "**/*.cljc"]}
                             project)
                      (update :source_paths vec)
                      (update :file_patterns vec)))
           config)]
     (m/assert config-spec config-with-defaults)
     config-with-defaults)))
