# Compact AST Format

## Overview

The compact AST format provides an alternative, space-efficient representation of Clojure Abstract Syntax Trees (ASTs). Inspired by [Pandoc's compact JSON AST design](https://pandoc.org/using-the-pandoc-api.html), this format achieves **~80-90% size reduction** compared to the verbose rewrite-clj serialization format.

## Motivation

### Problem: Verbose AST Storage

The current verbose format (using `llm-planner.ast/parse-clojure-string`) serializes rewrite-clj nodes as nested maps with full metadata:

```clojure
;; Verbose format for: (defn add [x y] (+ x y))
{:tag :forms
 :string "(defn add [x y] (+ x y))"
 :children [{:tag :list
             :string "(defn add [x y] (+ x y))"
             :children [{:tag :token :string "defn"}
                        {:tag :whitespace :string " "}
                        {:tag :token :string "add"}
                        {:tag :whitespace :string " "}
                        {:tag :vector
                         :string "[x y]"
                         :children [{:tag :token :string "x"}
                                    {:tag :whitespace :string " "}
                                    {:tag :token :string "y"}]}
                        {:tag :whitespace :string " "}
                        {:tag :list
                         :string "(+ x y)"
                         :children [{:tag :token :string "+"}
                                    {:tag :whitespace :string " "}
                                    {:tag :token :string "x"}
                                    {:tag :whitespace :string " "}
                                    {:tag :token :string "y"}]}]}]}

;; Size: ~600 characters
```

**Issues:**
- **Redundant data**: `:string` duplicates reconstructable information
- **Verbose keys**: Long field names (`:tag`, `:string`, `:children`)
- **Whitespace overhead**: Every space stored as separate node
- **Storage cost**: Large database size, slow queries
- **Memory usage**: Heavy runtime representation

### Solution: Compact Vector Format

The compact format uses **tag-first vectors** with minimal structure:

```clojure
;; Compact format for: (defn add [x y] (+ x y))
[:forms
  [:list [:tok 'defn] [:tok 'add]
         [:vec [:tok 'x] [:tok 'y]]
         [:list [:tok '+] [:tok 'x] [:tok 'y]]]]

;; Size: ~113 characters
;; Reduction: 81% smaller (5.3x compression)
```

**Benefits:**
- **Minimal structure**: Tag-first vectors, no redundant keys
- **No whitespace**: Omit formatting by default (optional preservation)
- **Direct values**: Store actual values in tokens, not strings
- **Fast operations**: Vector operations, easy destructuring
- **Memory efficient**: Smaller footprint, faster GC

## Design Principles

Inspired by Pandoc's AST design:

### 1. Compact Representation
- Use short, meaningful tags (`:tok`, `:list`, `:vec`, etc.)
- No redundant data that can be reconstructed
- Heterogeneous arrays mixing scalars and nested structures

### 2. Type-First Tagging
```clojure
[tag ...content]
```
- Tag appears first for fast dispatch
- Content follows naturally
- Easy pattern matching: `(case (first node) ...)`

### 3. Vector-Based Structure
- Vectors are fast, immutable, and idiomatic
- Easy destructuring: `(let [[tag & children] node] ...)`
- Efficient construction: `(into [tag] ...)`
- No record overhead

### 4. Optional Preservation
- Whitespace: off by default, opt-in for formatters
- Comments: off by default, opt-in for documentation
- Keeps common case minimal

## Format Specification

### Node Types

Every node is a vector: `[tag ...content]`

#### Token Nodes
Store atomic values directly:

```clojure
[:tok value]
```

Examples:
```clojure
[:tok 'foo]          ;; Symbol
[:tok :bar]          ;; Keyword
[:tok 42]            ;; Number
[:tok "hello"]       ;; String
[:tok true]          ;; Boolean
```

#### Collection Nodes
Store children recursively:

```clojure
[:list ...children]   ;; List ()
[:vec ...children]    ;; Vector []
[:map ...children]    ;; Map {}
[:set ...children]    ;; Set #{}
[:forms ...children]  ;; Top-level forms
```

Examples:
```clojure
;; List: (+ 1 2)
[:list [:tok '+] [:tok 1] [:tok 2]]

;; Vector: [x y z]
[:vec [:tok 'x] [:tok 'y] [:tok 'z]]

;; Map: {:a 1 :b 2}
[:map [:tok :a] [:tok 1] [:tok :b] [:tok 2]]

;; Set: #{1 2 3}
[:set [:tok 1] [:tok 2] [:tok 3]]
```

#### Metadata Nodes
Store metadata with the value:

```clojure
[:meta metadata-node value-node]
```

Example:
```clojure
;; ^{:doc "docstring"} foo
[:meta [:map [:tok :doc] [:tok "docstring"]] [:tok 'foo]]
```

#### Reader Macro Nodes
Store reader macros with tag and content:

```clojure
[:reader-macro tag-string content-node]
```

Examples:
```clojure
;; 'foo (quote)
[:reader-macro "'" [:tok 'foo]]

;; @atom (deref)
[:reader-macro "@" [:tok 'atom]]

;; #'var (var-quote)
[:reader-macro "#'" [:tok 'var]]

;; #(+ % 1) (anonymous function)
[:reader-macro "#" [:list [:tok '+] [:tok '%] [:tok 1]]]
```

#### Optional Nodes
Only included with explicit options:

```clojure
[:ws value]       ;; Whitespace (if :preserve-whitespace? true)
[:comment value]  ;; Comment (if :preserve-comments? true)
```

## API Reference

### Core Conversion

#### `compact-ast`
```clojure
(compact-ast code-str)
(compact-ast code-str opts)
```

Parse Clojure string to compact AST.

**Options:**
- `:preserve-whitespace?` - Include whitespace nodes (default: false)
- `:preserve-comments?` - Include comment nodes (default: false)

**Returns:** Compact AST vector or error map `{:error true :message "..." :input "..."}`

**Examples:**
```clojure
(compact-ast "(+ 1 2)")
;;=> [:forms [:list [:tok '+] [:tok 1] [:tok 2]]]

(compact-ast "(defn add [x y] (+ x y))")
;;=> [:forms [:list [:tok 'defn] [:tok 'add]
;;                  [:vec [:tok 'x] [:tok 'y]]
;;                  [:list [:tok '+] [:tok 'x] [:tok 'y]]]]
```

#### `compact-node`
```clojure
(compact-node node)
(compact-node node opts)
```

Convert rewrite-clj node to compact format (low-level API).

#### `reconstruct`
```clojure
(reconstruct compact-ast)
```

Reconstruct Clojure source code from compact AST.

**Example:**
```clojure
(def ast (compact-ast "(defn add [x y] (+ x y))"))
(reconstruct ast)
;;=> "(defn add [x y] (+ x y))"
```

### Node Navigation

#### `tag`
```clojure
(tag node)
```

Get the tag (type) of a compact AST node.

```clojure
(tag [:tok 'foo])    ;;=> :tok
(tag [:list ...])    ;;=> :list
```

#### `content`
```clojure
(content node)
```

Get the content (children) of a compact AST node.

```clojure
(content [:tok 'foo])              ;;=> ('foo)
(content [:list [:tok '+] [:tok 1]]) ;;=> ([:tok '+] [:tok 1])
```

### Node Predicates

```clojure
(token? node)           ;; Check if token node
(list-node? node)       ;; Check if list
(vec-node? node)        ;; Check if vector
(map-node? node)        ;; Check if map
(set-node? node)        ;; Check if set
(collection-node? node) ;; Check if any collection (including :forms)
```

### Form Predicates

```clojure
(defn-form? node)  ;; Check if (defn ...)
(def-form? node)   ;; Check if (def ...)
(ns-form? node)    ;; Check if (ns ...)
```

### Finding Forms

```clojure
(find-defns node)    ;; Find all defn forms
(find-defs node)     ;; Find all def forms
(find-ns-form node)  ;; Find ns form (first match)
```

**Example:**
```clojure
(def ast (compact-ast "(defn foo [x] x) (defn bar [y] y)"))
(find-defns ast)
;;=> [[:list [:tok 'defn] [:tok 'foo] ...]
;;    [:list [:tok 'defn] [:tok 'bar] ...]]
```

### Tree Walking

#### `walk`
```clojure
(walk f node)
```

Walk compact AST tree in pre-order, applying function to each node.

**Example:**
```clojure
;; Rename all 'x' symbols to 'y'
(walk (fn [node]
        (if (and (token? node) (= 'x (token-value node)))
          [:tok 'y]
          node))
      ast)
```

#### `postwalk`
```clojure
(postwalk f node)
```

Walk compact AST tree in post-order (children first).

#### `find-all`
```clojure
(find-all pred node)
```

Find all nodes matching predicate.

**Example:**
```clojure
;; Find all symbol tokens
(find-all #(and (token? %) (symbol? (token-value %))) ast)
```

### Size Analysis

#### `compare-sizes`
```clojure
(compare-sizes verbose-ast compact-ast)
```

Compare sizes between verbose and compact formats.

**Returns:**
```clojure
{:verbose-size 600
 :compact-size 113
 :reduction-bytes 487
 :reduction-percent 81.2
 :compression-ratio 5.3}
```

**Example:**
```clojure
(require '[llm-planner.ast :as ast])

(def code "(defn add [x y] (+ x y))")
(def verbose (ast/parse-clojure-string code))
(def compact (compact-ast code))

(compare-sizes verbose compact)
;;=> {:verbose-size 600, :compact-size 113, ...}
```

#### `node-count`
```clojure
(node-count node)
```

Count total number of nodes in compact AST.

#### `serialized-size`
```clojure
(serialized-size node)
```

Calculate approximate serialized size (in characters).

## Usage Examples

### Basic Parsing

```clojure
(require '[llm-planner.ast.compact :as compact])

;; Simple expression
(compact/compact-ast "(+ 1 2)")
;;=> [:forms [:list [:tok '+] [:tok 1] [:tok 2]]]

;; Function definition
(compact/compact-ast "(defn add [x y] (+ x y))")
;;=> [:forms [:list [:tok 'defn] [:tok 'add]
;;                  [:vec [:tok 'x] [:tok 'y]]
;;                  [:list [:tok '+] [:tok 'x] [:tok 'y]]]]

;; Namespace form
(compact/compact-ast "(ns my.app (:require [clojure.string :as str]))")
;;=> [:forms [:list [:tok 'ns] [:tok 'my.app]
;;                  [:list [:tok :require]
;;                         [:vec [:tok 'clojure.string] [:tok :as] [:tok 'str]]]]]
```

### Finding Definitions

```clojure
(def code "
(ns my.app)

(def config {:port 8080})

(defn start
  \"Starts the server\"
  []
  (println \"Starting...\"))

(defn stop
  \"Stops the server\"
  []
  (println \"Stopping...\"))
")

(def ast (compact/compact-ast code))

;; Find all defns
(compact/find-defns ast)
;;=> [[:list [:tok 'defn] [:tok 'start] ...]
;;    [:list [:tok 'defn] [:tok 'stop] ...]]

;; Find namespace
(compact/find-ns-form ast)
;;=> [:list [:tok 'ns] [:tok 'my.app]]

;; Extract function names
(map #(compact/token-value (nth % 2))
     (compact/find-defns ast))
;;=> ('start 'stop)
```

### Transforming ASTs

```clojure
;; Rename function
(def ast (compact/compact-ast "(defn foo [x] (+ x 1))"))

(compact/walk
  (fn [node]
    (if (and (compact/token? node)
             (= 'foo (compact/token-value node)))
      [:tok 'bar]  ; Rename foo to bar
      node))
  ast)

;; Reconstruct to see result
(compact/reconstruct renamed-ast)
;;=> "(defn bar [x] (+ x 1))"
```

### Size Comparison

```clojure
(require '[llm-planner.ast :as ast])

(def code "(defn factorial [n]
             (if (<= n 1)
               1
               (* n (factorial (dec n)))))")

(def verbose (ast/parse-clojure-string code))
(def compact (compact/compact-ast code))

(compact/compare-sizes verbose compact)
;;=> {:verbose-size 1247
;;    :compact-size 187
;;    :reduction-bytes 1060
;;    :reduction-percent 85.0
;;    :compression-ratio 6.67}

;; 85% reduction, 6.67x compression!
```

### Database Storage

```clojure
;; Store compact AST in database
(require '[clojure.data.json :as json])

(defn store-compact-ast! [db file-id content]
  (let [ast (compact/compact-ast content)]
    (if (map? ast)  ; Error?
      (println "Parse error:" (:message ast))
      (jdbc/execute-one!
        db
        (sql/format
          {:insert-into :file_content
           :values [{:file_id file-id
                     :content content
                     :compact_ast (json/write-str ast)}]})))))

;; Retrieve and reconstruct
(defn get-compact-ast [db content-id]
  (when-let [row (jdbc/execute-one!
                   db
                   (sql/format
                     {:select [:compact_ast]
                      :from [:file_content]
                      :where [:= :id content-id]}))]
    (json/read-str (:file_content/compact_ast row)
                   :key-fn keyword)))
```

## Performance Characteristics

### Space Complexity

| Metric | Verbose | Compact | Improvement |
|--------|---------|---------|-------------|
| Simple defn | ~600 chars | ~113 chars | **81% reduction** |
| Complex function | ~1200 chars | ~200 chars | **83% reduction** |
| Namespace form | ~800 chars | ~150 chars | **81% reduction** |
| Average | - | - | **~80-90% reduction** |

### Time Complexity

| Operation | Complexity | Notes |
|-----------|------------|-------|
| Parsing | O(n) | Linear in source code size |
| Tree walking | O(n) | Visit each node once |
| Finding forms | O(n) | Walk entire tree |
| Reconstruction | O(n) | Visit each node once |
| Node access | O(1) | Direct vector indexing |

### Memory Usage

- **Parsing**: No intermediate allocations beyond final AST
- **Walking**: No additional heap allocations (uses recursion)
- **Storage**: ~80-90% reduction vs verbose format

## Design Trade-offs

### Vector vs defrecord

**Current choice: Pure vectors**

| Aspect | Vectors (current) | defrecord |
|--------|------------------|-----------|
| Size | ✅ Smallest | ❌ Larger (map-like) |
| Speed | ✅ Fast enough | ✅ Faster field access |
| Pattern matching | ✅ Easy destructuring | ❌ Harder |
| Idiom | ✅ Very Clojure-like | ⚠️ More Java-like |
| Protocol support | ❌ No protocols | ✅ Protocol support |
| Serialization | ✅ Direct | ⚠️ Needs conversion |

**Rationale:**
- **Simplicity**: Vectors are simple, idiomatic, and well-understood
- **Proven design**: Pandoc uses the same approach successfully
- **Size matters**: Primary goal is space efficiency
- **Easy adoption**: No new types to learn
- **Future-proof**: Can add defrecord wrapper later if needed for performance

### Whitespace Preservation

**Current choice: Omit by default**

**Rationale:**
- **Common case**: Most use cases don't need exact formatting
- **Size reduction**: Major source of bloat in verbose format
- **Opt-in available**: `:preserve-whitespace? true` for formatters
- **Reconstructable**: Can reformat on reconstruction

**When to preserve:**
- Building code formatters
- Preserving exact original formatting
- Diffing tools that care about whitespace

### Comments Preservation

**Current choice: Omit by default**

**Rationale:**
- **Semantic focus**: Most analysis cares about code, not comments
- **Size reduction**: Comments can be large
- **Opt-in available**: `:preserve-comments? true` for documentation tools
- **Original preserved**: Source string still available in database

**When to preserve:**
- Extracting documentation
- Comment-based code generation
- Documentation tools

## Migration Guide

### From Verbose to Compact

```clojure
;; Old: Verbose format
(require '[llm-planner.ast :as ast])
(def verbose-ast (ast/parse-clojure-string code))
(def defns (ast/find-defns verbose-ast))

;; New: Compact format
(require '[llm-planner.ast.compact :as compact])
(def compact-ast (compact/compact-ast code))
(def defns (compact/find-defns compact-ast))
```

### Database Schema Changes

```sql
-- Add compact_ast column
ALTER TABLE file_content ADD COLUMN compact_ast TEXT;

-- Optionally migrate existing data
UPDATE file_content
SET compact_ast = (
  SELECT compact_serialize(ast_json)
  FROM file_content fc2
  WHERE fc2.id = file_content.id
);

-- Once migrated, can drop old ast_json column
-- ALTER TABLE file_content DROP COLUMN ast_json;
```

### Backward Compatibility

Both formats can coexist:

```clojure
;; Detect format automatically
(defn parse-ast [code-or-ast]
  (cond
    (string? code-or-ast)
    (compact/compact-ast code-or-ast)
    
    (and (map? code-or-ast) (:tag code-or-ast))
    ;; Convert verbose to compact
    (compact/compact-node (rewrite-clj.node/coerce code-or-ast))
    
    (vector? code-or-ast)
    ;; Already compact
    code-or-ast))
```

## Future Enhancements

### Potential defrecord Wrapper

For performance-critical operations:

```clojure
(defprotocol ASTNode
  (node-tag [this])
  (node-children [this]))

(defrecord ListNode [children]
  ASTNode
  (node-tag [_] :list)
  (node-children [this] children))

(defrecord TokenNode [value]
  ASTNode
  (node-tag [_] :tok)
  (node-children [_] nil))

;; Convert on demand for hot paths
(defn compact->record [compact-node]
  (let [[tag & content] compact-node]
    (case tag
      :list (->ListNode (map compact->record content))
      :tok (->TokenNode (first content))
      ...)))
```

**When to consider:**
- Benchmarks show significant performance bottleneck
- Protocol-based polymorphism is needed
- Field access dominates runtime

### Schema Validation with Malli

```clojure
(require '[malli.core :as m])

(def CompactNode
  [:multi {:dispatch first}
   [:tok [:tuple [:= :tok] :any]]
   [:list [:cat [:= :list] [:* [:ref ::CompactNode]]]]
   [:vec [:cat [:= :vec] [:* [:ref ::CompactNode]]]]
   [:map [:cat [:= :map] [:* [:ref ::CompactNode]]]]
   [:set [:cat [:= :set] [:* [:ref ::CompactNode]]]]
   [:forms [:cat [:= :forms] [:* [:ref ::CompactNode]]]]])

(defn validate-ast [ast]
  (m/validate CompactNode ast))
```

### Incremental Parsing

For large files:

```clojure
(defn compact-ast-lazy
  "Parse file incrementally, returning lazy sequence of top-level forms."
  [file-path]
  (map compact/compact-node
       (rewrite-clj.parser/parse-file-all file-path)))
```

## Testing

Comprehensive test suite in `test/llm_planner/ast/compact_test.clj`:

```bash
# Run all tests
clojure -M:test -m kaocha.runner

# Or via nREPL
(require '[clojure.test :as t])
(require '[llm-planner.ast.compact-test])
(t/run-tests 'llm-planner.ast.compact-test)
```

Test coverage:
- ✅ Basic node types (tokens, collections)
- ✅ Complex forms (defn, def, ns)
- ✅ Reconstruction (round-trip testing)
- ✅ Node predicates and queries
- ✅ Tree walking and transformation
- ✅ Size comparison
- ✅ Error handling
- ✅ Reader macros
- ✅ Whitespace/comment preservation

## Summary

The compact AST format provides:

- **Space efficiency**: 80-90% reduction vs verbose format
- **Performance**: Fast parsing, walking, and reconstruction
- **Simplicity**: Idiomatic vector-based structure
- **Flexibility**: Optional whitespace/comment preservation
- **Compatibility**: Can coexist with verbose format

**Use compact format when:**
- Storing ASTs in database
- Transmitting ASTs over network
- Memory-constrained environments
- Building analysis tools

**Use verbose format when:**
- Need full rewrite-clj node API
- Building code formatters (with `:preserve-whitespace? true` in compact)
- Existing code uses verbose format

**Recommendation:** Use compact format by default for new code, migrate existing code gradually.
