(ns dev
  (:require
   [clj-kondo.core :as clj-kondo]
   [clj-reload.core :as reload]
   [llm-planner.config :as config]
   [llm-planner.db :as db]))


(reload/init
 {:dirs ["src" "dev" "test"]})


(defn lint
  "Lint the entire project (src and test directories)."
  []
  (-> (clj-kondo/run! {:lint ["src" "test" "dev"]})
      (clj-kondo/print!)))


(defn refresh
  "Reloads and compiles he Clojure namespaces."
  []
  (reload/reload))

(def db-migration-config
  (db/migration-config
   (db/file-sqlite-database "test.db")))

(def project-config
  (config/load-config))

(comment
  (db/migrate db-migration-config))
