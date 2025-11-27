(ns llm-planner.main
  (:require
   [next.jdbc :as jdbc]
   [ragtime.next-jdbc :as ragtime-jdbc]
   [ragtime.repl :as ragtime-repl]
   [ragtime.reporter]
   [ragtime.strategy])
  (:import (org.sqlite SQLiteConnection))
  (:gen-class))

(defn migration-config
  "Given a SQLite Connection
   Returns a Ragtime migration config"
  [^SQLiteConnection connection]
  {:datastore  (ragtime-jdbc/sql-database connection)
   :migrations (ragtime-jdbc/load-resources "migrations")
   :reporter   ragtime.reporter/silent
   :strategy   ragtime.strategy/apply-new})

(defn memory-sqlite-database
  "Returns an in memory database"
  []
  (next.jdbc/get-connection
   {:connection-uri "jdbc:sqlite::memory:"}))

(defn file-sqlite-database
  [file]
  (next.jdbc/get-connection
   {:connection-uri (str "jdbc:sqlite:" file)}))

(defn migrate
  [config]
  (ragtime-repl/migrate config))

(defn rollback
  [config]
  (ragtime-repl/rollback config))

(defn rollback-all
  [config]
  (doseq [_ @ragtime-repl/migration-index]
    (ragtime-repl/rollback config)))

(defn -main [& args]
  (println "args: " args))
