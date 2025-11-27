(ns llm-planner.test-helper
  (:require [llm-planner.db :as db])
  (:import (org.sqlite SQLiteConnection)
           (org.sqlite.core DB)))

(def ^:dynamic *db* nil)
(def ^:dynamic *connection* nil)

(defn use-sqlite-database
  "A clojure.test fixture that sets up a in memory database
   After the test is run, this will rollback all of the migrations"
  [test-func]
  (let [conn     (db/memory-sqlite-database)
        database (.getDatabase ^SQLiteConnection conn)
        _        (.enable_load_extension ^DB database true)
        migration-config (db/migration-config conn)]
    (try
      (binding [*connection* conn
                *db*         database]
        (db/migrate migration-config)
        (test-func))
      (finally
        (db/rollback-all migration-config)
        (.close conn)))))
