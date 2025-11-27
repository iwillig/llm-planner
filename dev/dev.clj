(ns dev
  (:require
   [clj-kondo.core :as clj-kondo]
   [clj-reload.core :as reload]
   [llm-planner.main :as main]))


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

(def config
  (main/migration-config
   (main/file-sqlite-database "test.db")))

(comment
  (main/migrate config)
  )
