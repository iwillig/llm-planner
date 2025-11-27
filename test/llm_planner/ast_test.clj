(ns llm-planner.ast-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [llm-planner.ast :as ast]
   [llm-planner.db :as db]
   [next.jdbc :as jdbc]
   [honey.sql :as sql]))

(deftest test-parse-clojure-string
  (testing "Parsing Clojure code to AST"
    (let [code "(defn hello [name] (str \"Hello, \" name))"
          ast (ast/parse-clojure-string code)]
      (is (map? ast))
      (is (= :forms (:tag ast)))
      (is (contains? ast :children))
      (is (string? (:string ast))))))

(deftest test-ast-serialization
  (testing "AST serialization and deserialization"
    (let [code "(defn test [x] (+ x 1))"
          original-ast (ast/parse-clojure-string code)
          json-str (ast/ast->json original-ast)
          restored-ast (ast/json->ast json-str)]
      (is (= original-ast restored-ast))
      (is (= (:tag original-ast) (:tag restored-ast)))
      (is (= (:string original-ast) (:string restored-ast))))))

(deftest test-find-defns
  (testing "Finding defn forms in AST"
    (let [code "(ns test.core)
                (defn foo [x] (+ x 1))
                (defn bar [y] (* y 2))"
          ast (ast/parse-clojure-string code)
          defns (ast/find-defns ast)]
      (is (= 2 (count defns)))
      (is (= "foo" (:name (first defns))))
      (is (= "bar" (:name (second defns)))))))

(deftest test-find-namespace-form
  (testing "Finding namespace form"
    (let [code "(ns my.app.core
                  (:require [clojure.string :as str]))
                (defn test [])"
          ast (ast/parse-clojure-string code)
          ns-form (ast/find-namespace-form ast)]
      (is (some? ns-form))
      (is (= "my.app.core" (:name ns-form))))))

(deftest test-compare-defns
  (testing "Comparing defns between two versions"
    (let [old-code "(defn foo [x] (+ x 1))
                    (defn bar [y] (* y 2))"
          new-code "(defn foo [x] (+ x 2))
                    (defn bar [y] (* y 2))
                    (defn baz [z] (- z 1))"
          old-ast (ast/parse-clojure-string old-code)
          new-ast (ast/parse-clojure-string new-code)
          changes (ast/compare-defns old-ast new-ast)]
      (is (= 2 (count changes)))
      (is (some #(and (= (:change-type %) "update")
                      (= (:name %) "foo"))
                changes))
      (is (some #(and (= (:change-type %) "addition")
                      (= (:name %) "baz"))
                changes)))))

(deftest test-database-storage
  (testing "Storing and retrieving AST from database"
    (let [conn (db/memory-sqlite-database)
          _ (db/migrate (db/migration-config conn))
          code "(defn hello [name]
                  (str \"Hello, \" name))"
          ;; Create project and file
          _ (jdbc/execute! conn
                           (sql/format
                            {:insert-into :project
                             :values [{:name "Test" :path "/tmp" :description "Test"}]}))
          _ (jdbc/execute! conn
                           (sql/format
                            {:insert-into :file
                             :values [{:project_id 1 :path "test.clj" :summary "Test"}]}))
          ;; Store content with AST
          content-id (ast/store-file-content! conn 1 code)
          ;; Retrieve AST
          retrieved-ast (ast/get-file-content-ast conn content-id)]
      (is (some? content-id))
      (is (map? retrieved-ast))
      (is (= :forms (:tag retrieved-ast)))
      ;; Verify we can find defns in retrieved AST
      (let [defns (ast/find-defns retrieved-ast)]
        (is (= 1 (count defns)))
        (is (= "hello" (:name (first defns))))))))

(deftest test-store-defn-changes
  (testing "Storing defn changes in database"
    (let [conn (db/memory-sqlite-database)
          _ (db/migrate (db/migration-config conn))
          old-code "(defn greet [name] (str \"Hello, \" name))"
          new-code "(defn greet [name] (str \"Hi, \" name))
                    (defn farewell [name] (str \"Bye, \" name))"
          ;; Setup database
          _ (jdbc/execute! conn
                           (sql/format
                            {:insert-into :project
                             :values [{:name "Test" :path "/tmp" :description "Test"}]}))
          _ (jdbc/execute! conn
                           (sql/format
                            {:insert-into :plan
                             :values [{:project_id 1 :name "Test Plan" :context "Test"}]}))
          _ (jdbc/execute! conn
                           (sql/format
                            {:insert-into :file
                             :values [{:project_id 1 :path "test.clj" :summary "Test"}]}))
          _ (jdbc/execute! conn
                           (sql/format
                            {:insert-into :file_change
                             :values [{:plan_id 1 :file_id 1}]}))
          ;; Store changes
          changes (ast/store-defn-changes! conn 1 old-code new-code)
          ;; Query stored changes
          stored (jdbc/execute! conn
                                (sql/format
                                 {:select [:*]
                                  :from [:file_change_ast]
                                  :where [:= :file_change_id 1]}))]
      (is (= 2 (count changes)))
      (is (= 2 (count stored)))
      ;; Verify update change
      (is (some #(and (= (:file_change_ast/change_type %) "update")
                      (= (:file_change_ast/node_tag %) "defn"))
                stored))
      ;; Verify addition change
      (is (some #(and (= (:file_change_ast/change_type %) "addition")
                      (= (:file_change_ast/node_tag %) "defn"))
                stored)))))

(deftest test-query-forms-by-type
  (testing "Querying stored forms by type"
    (let [conn (db/memory-sqlite-database)
          _ (db/migrate (db/migration-config conn))
          ;; Setup
          _ (jdbc/execute! conn
                           (sql/format
                            {:insert-into :project
                             :values [{:name "Test" :path "/tmp" :description "Test"}]}))
          _ (jdbc/execute! conn
                           (sql/format
                            {:insert-into :plan
                             :values [{:project_id 1 :name "Test Plan" :context "Test"}]}))
          _ (jdbc/execute! conn
                           (sql/format
                            {:insert-into :file
                             :values [{:project_id 1 :path "test.clj" :summary "Test"}]}))
          _ (jdbc/execute! conn
                           (sql/format
                            {:insert-into :file_change
                             :values [{:plan_id 1 :file_id 1}]}))
          ;; Store changes (foo removed, bar added = 2 changes)
          _ (ast/store-defn-changes! conn 1
                                     "(defn foo [] 1)"
                                     "(defn bar [] 2)")
          ;; Query
          results (ast/query-forms-by-type conn "defn")]
      (is (= 2 (count results)))
      (is (every? #(= "defn" (:file_change_ast/node_tag %)) results)))))

(deftest test-reconstruct-source
  (testing "Reconstructing source from AST preserves formatting"
    (let [original-code "(defn hello
  ;; A friendly greeting
  [name]
  (str \"Hello, \" name))"
          ast (ast/parse-clojure-string original-code)
          reconstructed (ast/reconstruct-source-from-ast ast)]
      (is (= original-code reconstructed)))))
