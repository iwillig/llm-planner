(ns llm-planner.ast.compact-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm-planner.ast.compact :as compact]))

(deftest test-compact-ast-basic
  (testing "Basic token"
    (is (= [:forms [:tok 'foo]]
           (compact/compact-ast "foo"))))

  (testing "Simple list"
    (is (= [:forms [:list [:tok '+] [:tok 1] [:tok 2]]]
           (compact/compact-ast "(+ 1 2)"))))

  (testing "Vector"
    (is (= [:forms [:vec [:tok 1] [:tok 2] [:tok 3]]]
           (compact/compact-ast "[1 2 3]"))))

  (testing "Map"
    (is (= [:forms [:map [:tok :a] [:tok 1] [:tok :b] [:tok 2]]]
           (compact/compact-ast "{:a 1 :b 2}"))))

  (testing "Set"
    (is (= [:forms [:set [:tok 1] [:tok 2] [:tok 3]]]
           (compact/compact-ast "#{1 2 3}")))))

(deftest test-compact-ast-defn
  (testing "Simple defn"
    (let [ast (compact/compact-ast "(defn add [x y] (+ x y))")]
      (is (= :forms (compact/tag ast)))
      (is (= 1 (count (compact/find-defns ast))))))

  (testing "defn with docstring"
    (let [ast (compact/compact-ast "(defn add \"Adds two numbers\" [x y] (+ x y))")]
      (is (= 1 (count (compact/find-defns ast))))
      (let [defn-node (first (compact/find-defns ast))]
        ;; Structure: [:list [:tok defn] [:tok add] [:tok "Adds..."] [:vec ...] [:list ...]]
        ;; Index 0 = :list, Index 1 = [:tok defn], Index 2 = [:tok add], Index 3 = [:tok "Adds..."]
        (is (= "Adds two numbers" (compact/token-value (nth defn-node 3)))))))

  (testing "Multiple defns"
    (let [ast (compact/compact-ast "(defn foo [x] x) (defn bar [y] (+ y 1))")]
      (is (= 2 (count (compact/find-defns ast)))))))

(deftest test-compact-ast-ns
  (testing "Simple ns"
    (let [ast (compact/compact-ast "(ns my.app)")]
      (is (not (nil? (compact/find-ns-form ast))))
      ;; Structure: [:list [:tok ns] [:tok my.app]]
      ;; Index 0 = :list, Index 1 = [:tok ns], Index 2 = [:tok my.app]
      (is (= 'my.app (compact/token-value (nth (compact/find-ns-form ast) 2))))))

  (testing "ns with requires"
    (let [ast (compact/compact-ast "(ns my.app (:require [clojure.string :as str]))")]
      (is (not (nil? (compact/find-ns-form ast)))))))

(deftest test-reconstruction
  (testing "Simple list reconstruction"
    (let [code "(+ 1 2)"
          ast (compact/compact-ast code)
          reconstructed (compact/reconstruct ast)]
      (is (= code reconstructed))))

  (testing "defn reconstruction"
    (let [code "(defn add [x y] (+ x y))"
          ast (compact/compact-ast code)
          reconstructed (compact/reconstruct ast)]
      (is (= code reconstructed))))

  (testing "ns reconstruction"
    (let [code "(ns my.app (:require [clojure.string :as str]))"
          ast (compact/compact-ast code)
          reconstructed (compact/reconstruct ast)]
      (is (= code reconstructed)))))

(deftest test-node-predicates
  (testing "token?"
    (is (compact/token? [:tok 'foo]))
    (is (not (compact/token? [:list [:tok '+]]))))

  (testing "list-node?"
    (is (compact/list-node? [:list [:tok '+]]))
    (is (not (compact/list-node? [:vec [:tok 1]]))))

  (testing "vec-node?"
    (is (compact/vec-node? [:vec [:tok 1]]))
    (is (not (compact/vec-node? [:list [:tok '+]]))))

  (testing "collection-node?"
    (is (compact/collection-node? [:list [:tok '+]]))
    (is (compact/collection-node? [:vec [:tok 1]]))
    (is (compact/collection-node? [:map [:tok :a] [:tok 1]]))
    (is (compact/collection-node? [:set [:tok 1]]))
    (is (compact/collection-node? [:forms [:tok 1]]))
    (is (not (compact/collection-node? [:tok 'foo])))))

(deftest test-form-predicates
  (testing "defn-form?"
    (let [ast (compact/compact-ast "(defn foo [x] x)")]
      (is (compact/defn-form? (second ast)))))

  (testing "def-form?"
    (let [ast (compact/compact-ast "(def x 10)")]
      (is (compact/def-form? (second ast)))))

  (testing "ns-form?"
    (let [ast (compact/compact-ast "(ns my.app)")]
      (is (compact/ns-form? (second ast))))))

(deftest test-size-comparison
  (testing "Compact AST is reasonably sized"
    (let [code "(defn add [x y] (+ x y))"
          compact-ast (compact/compact-ast code)
          size (compact/serialized-size compact-ast)]
      ;; Compact AST should be under 150 chars for this simple defn
      (is (< size 150))
      ;; Should be able to count nodes (11 = :forms + :list + 5 :tok + :vec + 2 :tok + :list + 3 :tok)
      (is (= 11 (compact/node-count compact-ast))))))

(deftest test-walk
  (testing "Walk replaces tokens"
    (let [ast [:list [:tok 'x] [:tok 'y]]
          result (compact/walk
                  (fn [node]
                    (if (and (compact/token? node)
                             (= 'x (compact/token-value node)))
                      [:tok 'z]
                      node))
                  ast)]
      (is (= [:list [:tok 'z] [:tok 'y]] result))))

  (testing "Walk visits all nodes"
    (let [ast [:list [:tok 'defn] [:tok 'foo]
               [:vec [:tok 'x]]
               [:list [:tok '+] [:tok 'x] [:tok 1]]]
          visited (atom [])
          _ (compact/walk
             (fn [node]
               (swap! visited conj (compact/tag node))
               node)
             ast)]
      (is (= [:list :tok :tok :vec :tok :list :tok :tok :tok]
             @visited)))))

(deftest test-find-all
  (testing "Find all tokens"
    (let [ast [:list [:tok 'defn] [:tok 'foo] [:vec [:tok 'x]]]
          tokens (compact/find-all compact/token? ast)]
      (is (= 3 (count tokens)))
      (is (every? compact/token? tokens))))

  (testing "Find all symbols"
    (let [ast [:list [:tok 'defn] [:tok 'foo] [:tok 42] [:vec [:tok 'x]]]
          symbols (compact/find-all
                   #(and (compact/token? %)
                         (symbol? (compact/token-value %)))
                   ast)]
      (is (= 3 (count symbols)))
      (is (= ['defn 'foo 'x]
             (map compact/token-value symbols))))))

(deftest test-error-handling
  (testing "Parse error returns error map"
    (let [result (compact/compact-ast "(defn broken")]
      (is (map? result))
      (is (:error result))
      (is (:message result))
      (is (:input result)))))

(deftest test-reader-macros
  (testing "Quote"
    (let [ast (compact/compact-ast "'foo")]
      (is (= [:forms [:reader-macro "'" [:tok 'foo]]] ast))))

  (testing "Deref"
    (let [ast (compact/compact-ast "@atom")]
      (is (= [:forms [:reader-macro "@" [:tok 'atom]]] ast))))

  (testing "Var quote"
    (let [ast (compact/compact-ast "#'foo")]
      (is (= [:forms [:reader-macro "#'" [:tok 'foo]]] ast)))))

(deftest test-node-count
  (testing "Count tokens"
    (let [ast [:tok 'foo]]
      (is (= 1 (compact/node-count ast)))))

  (testing "Count collection nodes"
    (let [ast [:list [:tok 'defn] [:tok 'foo] [:vec [:tok 'x]]]]
      ;; :list + defn + foo + :vec + x = 5 nodes
      (is (= 5 (compact/node-count ast))))))

(deftest test-serialized-size
  (testing "Calculate serialized size"
    (let [ast [:tok 'foo]
          size (compact/serialized-size ast)]
      (is (pos? size))
      (is (< size 20)))))  ; [:tok foo] is small

(deftest test-preserve-whitespace
  (testing "Preserve whitespace in compact AST"
    (let [ast (compact/compact-ast "(+ 1 2)" {:preserve-whitespace? true})]
      ;; Should include whitespace nodes
      (is (some #(= :ws (compact/tag %))
                (compact/find-all (constantly true) ast))))))

(deftest test-preserve-comments
  (testing "Preserve comments in compact AST"
    (let [ast (compact/compact-ast ";; comment\n(+ 1 2)" {:preserve-comments? true})]
      ;; Should include comment nodes
      (is (some #(= :comment (compact/tag %))
                (compact/find-all (constantly true) ast))))))
