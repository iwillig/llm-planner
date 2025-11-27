(ns llm-planner.main-test
  (:require [clojure.test :as t :refer [deftest is testing]]
            [llm-planner.test-helper :as test-helper]))

(t/use-fixtures :each test-helper/use-sqlite-database)

(deftest test-okay
  (testing "Given: A valid context"
    (testing "When: We check if false is nil?"
      (is (false? (nil? false))
          "Then: We execept to get back false"))))
