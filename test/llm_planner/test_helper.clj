(ns llm-planner.test-helper
  (:require [llm-planner.main :as main])
  (:import (org.sqlite SQLiteConnection)
           (org.sqlite.core DB)))

(def ^:dynamic *db* nil)
(def ^:dynamic *connection* nil)

(defn use-sqlite-database
  "A clojure.test fixture that sets up a in memory database
   After the test is run, this will rollback all of the migrations"
  [test-func]
  (let [conn     (main/memory-sqlite-database)
        database (.getDatabase ^SQLiteConnection conn)
        _        (.enable_load_extension ^DB database true)
        migration-config (main/migration-config conn)]
    (try
      (binding [*connection* conn
                *db*         database]
        (main/migrate migration-config)
        (test-func))
      (finally
        (main/rollback-all migration-config)
        (.close conn)))))
