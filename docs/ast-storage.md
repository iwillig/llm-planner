# AST Storage with rewrite-clj and SQLite

## Overview

The `llm-planner.ast` namespace provides functionality for parsing
Clojure code using rewrite-clj, serializing the AST to JSON, and
storing it in SQLite using JSONB format. This enables powerful code
analysis, change tracking, and querying capabilities.

## Key Features

- **Parse Clojure code** into AST that preserves formatting, whitespace, and comments
- **Serialize AST to JSON** for storage in SQLite
- **Round-trip serialization** - deserialize JSON back to AST
- **Analyze code structure** - find defns, defs, namespace forms
- **Compare code versions** - detect additions, removals, and updates
- **Store in database** - persist AST and changes in SQLite
- **Query with SQL** - use SQLite's JSON functions to query AST data

## Database Schema

### file_content Table

Stores complete file content with parsed AST:

```sql
CREATE TABLE file_content (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  file_id INTEGER NOT NULL REFERENCES file(id) ON DELETE CASCADE,
  content TEXT NOT NULL,                    -- Original source code
  ast_json TEXT,                            -- Parsed AST as JSON
  parsed_at TEXT NOT NULL DEFAULT (datetime('now')),
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at TEXT NOT NULL DEFAULT (datetime('now'))
)
```

### file_change_ast Table

Stores individual form changes (defn, def, etc.) for tracking code evolution:

```sql
CREATE TABLE file_change_ast (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  file_change_id INTEGER NOT NULL REFERENCES file_change(id) ON DELETE CASCADE,
  node_path TEXT NOT NULL,                  -- e.g., "defn[greet]"
  node_tag TEXT NOT NULL,                   -- e.g., "defn"
  node_string TEXT NOT NULL,                -- Source code of the node
  node_ast_json TEXT,                       -- AST of this specific node
  change_type TEXT CHECK(change_type IN ('addition', 'removal', 'update')),
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at TEXT NOT NULL DEFAULT (datetime('now'))
)
```

## AST Structure

The serialized AST is a nested map structure:

```clojure
{:tag :forms                          ; Node type (keyword)
 :string "(defn hello ...)"           ; Source code representation
 :children [{:tag :list               ; Child nodes
             :string "(defn hello [name] ...)"
             :children [...]}]}
```

**Note:** We intentionally **exclude** `:value` (s-expression) from serialization to avoid issues with symbol/keyword serialization in JSON.

## API Reference

### Parsing Functions

#### `parse-clojure-string`
```clojure
(ast/parse-clojure-string code-string)
;; => AST map
```

Parse Clojure source code string into AST.

**Example:**
```clojure
(def ast (ast/parse-clojure-string "(defn hello [name] (str \"Hello, \" name))"))
```

#### `parse-clojure-file`
```clojure
(ast/parse-clojure-file "/path/to/file.clj")
;; => AST map
```

Parse a Clojure file into AST.

### Serialization Functions

#### `ast->json`
```clojure
(ast/ast->json ast-map)
;; => JSON string
```

Convert AST to JSON string for storage.

#### `json->ast`
```clojure
(ast/json->ast json-string)
;; => AST map
```

Parse JSON string back to AST map with proper keyword types.

### Analysis Functions

#### `find-defns`
```clojure
(ast/find-defns ast-map)
;; => [{:name "hello"
;;      :full-form "(defn hello ...)"
;;      :children [...]}]
```

Find all `defn` forms in AST.

#### `find-defs`
```clojure
(ast/find-defs ast-map)
```

Find all `def` forms in AST.

#### `find-namespace-form`
```clojure
(ast/find-namespace-form ast-map)
;; => {:name "my.app.core" :full-form "(ns my.app.core ...)" ...}
```

Find the namespace form in AST.

#### `extract-top-level-forms`
```clojure
(ast/extract-top-level-forms ast-map)
;; => [{:form-type "ns" :full-form "..." :tag :list}
;;     {:form-type "defn" :full-form "..." :tag :list}]
```

Extract all top-level forms (ns, defn, def, etc.).

### Comparison Functions

#### `compare-defns`
```clojure
(ast/compare-defns old-ast new-ast)
;; => [{:change-type "update" :name "foo" :old-form "..." :new-form "..."}
;;     {:change-type "addition" :name "bar" :new-form "..."}
;;     {:change-type "removal" :name "baz" :old-form "..."}]
```

Compare defn forms between two ASTs and detect changes.

**Change types:**
- `"addition"` - New function added
- `"removal"` - Function removed
- `"update"` - Function modified

### Database Functions

#### `store-file-content!`
```clojure
(ast/store-file-content! db file-id source-code)
;; => content-id
```

Store file content with parsed AST in database. Returns the ID of the inserted record.

**Example:**
```clojure
(def content-id (ast/store-file-content! conn 1 "(defn hello [] \"world\")"))
```

#### `get-file-content-ast`
```clojure
(ast/get-file-content-ast db content-id)
;; => AST map
```

Retrieve parsed AST for a file content record.

**Example:**
```clojure
(def ast (ast/get-file-content-ast conn content-id))
(ast/find-defns ast) ;; Query the retrieved AST
```

#### `store-defn-changes!`
```clojure
(ast/store-defn-changes! db file-change-id old-content new-content)
;; => [change-maps...]
```

Compare old and new content, detect defn changes, and store them in `file_change_ast` table.

**Example:**
```clojure
(def changes (ast/store-defn-changes!
               conn
               file-change-id
               "(defn old-fn [])"
               "(defn new-fn [])"))
;; => [{:change-type "removal" :name "old-fn" ...}
;;     {:change-type "addition" :name "new-fn" ...}]
```

#### `query-forms-by-type`
```clojure
(ast/query-forms-by-type db "defn")
;; => [{:file_change_ast/id 1
;;      :file_change_ast/node_path "defn[hello]"
;;      :file_change_ast/node_tag "defn"
;;      :file_change_ast/node_string "(defn hello ...)"
;;      :file_change_ast/change_type "addition"}]
```

Query all stored forms of a specific type.

### Utility Functions

#### `reconstruct-source-from-ast`
```clojure
(ast/reconstruct-source-from-ast ast-map)
;; => Original source code string
```

Reconstruct source code from AST. Preserves all formatting, whitespace, and comments.

## Usage Examples

### Example 1: Parse and Store File Content

```clojure
(require '[llm-planner.ast :as ast]
         '[llm-planner.db :as db]
         '[next.jdbc :as jdbc])

;; Create database connection
(def conn (db/file-sqlite-database {:db_path "planner.db"}))
(db/migrate (db/migration-config conn))

;; Assuming you have a file record with id=1
(def source-code
  "(ns my.app.core
     (:require [clojure.string :as str]))

   (defn greet [name]
     (str \"Hello, \" name))

   (defn farewell [name]
     (str \"Goodbye, \" name))")

;; Store content with AST
(def content-id (ast/store-file-content! conn 1 source-code))

;; Retrieve and analyze
(def ast (ast/get-file-content-ast conn content-id))
(def defns (ast/find-defns ast))
(println "Found" (count defns) "functions")
;; => Found 2 functions
```

### Example 2: Track Code Changes

```clojure
;; Old version
(def old-code
  "(defn greet [name]
     (str \"Hello, \" name))

   (defn farewell [name]
     (str \"Goodbye, \" name))")

;; New version
(def new-code
  "(defn greet [name]
     (str \"Hi, \" name))  ;; Changed greeting

   (defn farewell [name]
     (str \"Goodbye, \" name))

   (defn welcome [name]
     (str \"Welcome, \" name))  ;; New function")

;; Compare and store changes
(def changes (ast/store-defn-changes! conn file-change-id old-code new-code))

;; Inspect changes
(doseq [change changes]
  (println (:change-type change) "-" (:name change)))
;; => update - greet
;; => addition - welcome
```

### Example 3: Query with SQLite JSON Functions

```clojure
;; Find all function names that were added
(jdbc/execute!
  conn
  ["SELECT json_extract(node_ast_json, '$.name') as func_name,
           change_type
    FROM file_change_ast
    WHERE node_tag = 'defn'
      AND change_type = 'addition'"])

;; Query AST structure
(jdbc/execute!
  conn
  ["SELECT json_extract(ast_json, '$.tag') as root_tag,
           json_array_length(ast_json, '$.children') as num_children
    FROM file_content
    WHERE id = ?"]
  [content-id])
```

### Example 4: Find All Functions in a Project

```clojure
;; Find all defns across all file content records
(defn find-all-project-functions [db project-id]
  (let [files (jdbc/execute!
                db
                (sql/format
                  {:select [:fc.id :fc.ast_json :f.path]
                   :from [[:file_content :fc]]
                   :join [[:file :f] [:= :fc.file_id :f.id]]
                   :where [:= :f.project_id project-id]}))]
    (mapcat
      (fn [file]
        (let [ast (ast/json->ast (:file_content/ast_json file))
              defns (ast/find-defns ast)]
          (map #(assoc % :file (:file/path file)) defns)))
      files)))

;; Use it
(def all-functions (find-all-project-functions conn 1))
(doseq [func all-functions]
  (println (:file func) "-" (:name func)))
```

### Example 5: Analyze Code Evolution

```clojure
;; Track how a specific function evolved
(defn function-history [db function-name]
  (jdbc/execute!
    db
    (sql/format
      {:select [:fca.change_type :fca.node_string :fca.created_at]
       :from [[:file_change_ast :fca]]
       :where [:and
               [:= :fca.node_tag "defn"]
               [:= [:json_extract :fca.node_ast_json "$.name"] function-name]]
       :order-by [[:fca.created_at :desc]]})))

;; Use it
(function-history conn "greet")
;; => [{:change_type "update" :node_string "(defn greet ...)" :created_at "..."}
;;     {:change_type "addition" :node_string "(defn greet ...)" :created_at "..."}]
```

## Benefits

1. **Preserves Formatting** - Unlike `read-string`, rewrite-clj preserves whitespace, comments, and formatting
2. **Queryable AST** - SQLite's JSON functions enable complex queries on code structure
3. **Change Tracking** - Precisely track what changed (additions, removals, updates)
4. **Code Analysis** - Analyze code patterns, find functions, extract metadata
5. **Reproducible** - Round-trip serialization ensures data integrity
6. **Performant** - Indexed queries on `node_tag` and other fields

## Testing

Run tests with:

```bash
clj -M:test
```

Or in the REPL:

```clojure
(require '[llm-planner.ast-test :as ast-test] :reload)
(clojure.test/run-tests 'llm-planner.ast-test)
```

## Implementation Notes

### Why Exclude `:value` from Serialization?

The `:value` field contains the s-expression representation, which includes Clojure-specific types (symbols, keywords) that don't serialize cleanly to JSON. Instead, we:

1. Store `:tag` (keyword) for node type
2. Store `:string` for source representation
3. Store `:children` recursively

This is sufficient for reconstruction and analysis while avoiding serialization complexity.

### JSON vs JSONB in SQLite

SQLite doesn't have a separate JSONB type like PostgreSQL. Instead, it stores JSON as TEXT and provides JSON functions (`json_extract`, `json_array_length`, etc.) for querying.

For better performance, you can:
- Add indexes on commonly queried JSON paths
- Use generated columns for frequently accessed JSON fields
- Consider storing critical fields (like function names) as regular columns

## Future Enhancements

Potential improvements:

1. **Generated Columns** - Extract function names to indexed columns
2. **Full-Text Search** - Index code content for text search
3. **Diff Visualization** - Generate visual diffs from AST changes
4. **Semantic Analysis** - Analyze dependencies, call graphs, etc.
5. **Refactoring Support** - Use AST to automate refactorings
6. **Multi-Language** - Extend to ClojureScript, EDN files

## Resources

- [rewrite-clj Documentation](https://github.com/clj-commons/rewrite-clj)
- [SQLite JSON Functions](https://www.sqlite.org/json1.html)
- [HoneySQL Guide](https://github.com/seancorfield/honeysql)
- [next.jdbc Guide](https://github.com/seancorfield/next-jdbc)
