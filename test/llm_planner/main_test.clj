(ns llm-planner.main-test
  (:require [clojure.test :as t :refer [deftest is testing]]))

(deftest test-okay
  (testing "Given: A valid context"
    (testing "When: We check if false is nil?"
      (is (false? (nil? false))
          "Then: We execept to get back false"))))
