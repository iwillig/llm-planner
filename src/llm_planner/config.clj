(ns llm-planner.config
  (:require [clj-yaml.core :as yaml]
            [malli.core :as m]))

(def default-path ".llm-planner/config.yml")

(def db-config-spec
  [:map {:closed true}
   [:db_path :string]])

(defn load-config
  ([]
   (load-config {:config-path default-path}))
  ([{:keys [config-path]}]
   (let [config-content (slurp config-path)
         edn (yaml/parse-string config-content)]
     (m/assert db-config-spec edn)
     edn)))
