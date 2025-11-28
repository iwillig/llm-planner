(ns llm-planner.ast-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [llm-planner.ast :as ast]
   [llm-planner.db :as db]
   [next.jdbc :as jdbc]
   [honey.sql :as sql]))

(deftest test-parse-string
  (testing "Parsing Clojure code to AST"
    (let [code "(defn hello [name] (str \"Hello, \" name))"
          result (ast/parse-string code)]
      (is (vector? result))
      (is (= :forms (first result)))
      (is (= 1 (count (ast/find-defns result)))))))

(deftest test-parse-error
  (testing "Parse errors return error map"
    (let [result (ast/parse-string "(defn broken")]
      (is (map? result))
      (is (:error result))
      (is (:message result))
      (is (:input result)))))

(deftest test-ast-serialization
  (testing "AST serialization and deserialization"
    (let [code "(defn test [x] (+ x 1))"
          original-ast (ast/parse-string code)
          json-str (ast/ast->json original-ast)
          restored-ast (ast/json->ast json-str)]
      (is (= original-ast restored-ast))
      (is (= (first original-ast) (first restored-ast))))))

(deftest test-find-defns
  (testing "Finding defn forms in AST"
    (let [code "(ns test.core)
                (defn foo [x] (+ x 1))
                (defn bar [y] (* y 2))"
          defns (ast/find-defns code)]
      (is (= 2 (count defns)))
      (is (= 'foo (:name (first defns))))
      (is (= 'bar (:name (second defns))))))

  (testing "Finding defns with docstrings"
    (let [code "(defn greet \"Says hello\" [name] (str \"Hello, \" name))"
          defns (ast/find-defns code)]
      (is (= 1 (count defns)))
      (is (= 'greet (:name (first defns))))
      (is (= "Says hello" (:docstring (first defns)))))))

(deftest test-find-defs
  (testing "Finding def forms"
    (let [code "(def x 10) (def y 20)"
          defs (ast/find-defs code)]
      (is (= 2 (count defs)))
      (is (= 'x (:name (first defs))))
      (is (= 'y (:name (second defs)))))))

(deftest test-find-namespace
  (testing "Finding namespace form"
    (let [code "(ns my.app.core
                  (:require [clojure.string :as str]))
                (defn test [])"
          ns-form (ast/find-namespace code)]
      (is (some? ns-form))
      (is (= 'my.app.core (:name ns-form)))
      (is (= [['clojure.string :as 'str]] (:requires ns-form)))))

  (testing "Finding namespace with docstring"
    (let [code "(ns my.app \"My application\" (:require [clojure.string]))"
          ns-form (ast/find-namespace code)]
      (is (= 'my.app (:name ns-form)))
      (is (= "My application" (:docstring ns-form))))))

(deftest test-compare-defns
  (testing "Comparing defns between two versions"
    (let [old-code "(defn foo [x] (+ x 1))
                    (defn bar [y] (* y 2))"
          new-code "(defn foo [x] (+ x 2))
                    (defn bar [y] (* y 2))
                    (defn baz [z] (- z 1))"
          changes (ast/compare-defns old-code new-code)]
      (is (= 2 (count changes)))
      (is (some #(and (= (:change-type %) "update")
                      (= (:name %) 'foo))
                changes))
      (is (some #(and (= (:change-type %) "addition")
                      (= (:name %) 'baz))
                changes))))

  (testing "Comparing with removal"
    (let [old-code "(defn foo [] 1) (defn bar [] 2)"
          new-code "(defn bar [] 2)"
          changes (ast/compare-defns old-code new-code)]
      (is (= 1 (count changes)))
      (is (= "removal" (:change-type (first changes))))
      (is (= 'foo (:name (first changes)))))))

(deftest test-reconstruct
  (testing "Reconstructing source from AST"
    (let [code "(defn hello [name] (str \"Hello, \" name))"
          ast-result (ast/parse-string code)
          reconstructed (ast/reconstruct ast-result)]
      ;; Reconstruction may not preserve exact formatting but should be valid
      (is (string? reconstructed))
      (is (.contains reconstructed "defn"))
      (is (.contains reconstructed "hello")))))

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
      (is (vector? retrieved-ast))
      (is (= :forms (first retrieved-ast)))
      ;; Verify we can find defns in retrieved AST
      (let [defns (ast/find-defns retrieved-ast)]
        (is (= 1 (count defns)))
        (is (= 'hello (:name (first defns))))))))

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

(deftest test-parse-file
  (testing "Parsing a file"
    ;; Create a temp file for testing
    (let [temp-file (java.io.File/createTempFile "test" ".clj")]
      (try
        (spit temp-file "(defn test-fn [] :ok)")
        (let [result (ast/parse-file (.getPath temp-file))]
          (is (vector? result))
          (is (= 1 (count (ast/find-defns result)))))
        (finally
          (.delete temp-file))))))

(deftest test-working-with-ast-directly
  (testing "Working with parsed AST"
    (let [ast (ast/parse-string "(defn foo [x] x) (defn bar [y] y)")
          defns (ast/find-defns ast)]
      (is (= 2 (count defns)))
      ;; Can extract name and source from each defn
      (is (every? :name defns))
      (is (every? :source defns))
      (is (every? :node defns)))))
