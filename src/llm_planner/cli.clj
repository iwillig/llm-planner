(ns llm-planner.cli
  (:require [cli-matic.core :as cli-matic]
            [clojure.pprint :as pp]
            [llm-planner.db :as db]
            [llm-planner.config :as config]))


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




(def ^:private COMMAND-CONFIG
  {:command "llm-planner"
   :version "0.0.1"
   :opts        [{:as      "config-path"
                  :default ".llm-planner/config.yml"
                  :option  "config-path"
                  :type    :string}]
   :description "A tool for working with LLM tools"
   :subcommands [DB-CONFIG CONFIG-CONFIG]})

(defn main-fn
  [args]
  (cli-matic/run-cmd args COMMAND-CONFIG))
