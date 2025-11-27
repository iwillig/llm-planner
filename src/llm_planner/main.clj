(ns llm-planner.main
  (:gen-class)
  (:require
   [llm-planner.cli :as cli]
   [ragtime.reporter]
   [ragtime.strategy]))

(defn -main [& args]
  (cli/main-fn args))
