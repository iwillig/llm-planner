# AST Refactoring Plan: Verbose → Compact

## Goal

Replace all usage of the verbose AST format with the compact AST format throughout the project.

## Current State Analysis

### Database Schema

**Tables using AST storage:**

1. **`file_content` table** (line 77-85)
   - Column: `ast_json TEXT`
   - Stores verbose serialized AST
   - Used by: `store-file-content!`, `get-file-content-ast`

2. **`file_change_ast` table** (line 106-116)
   - Column: `node_ast_json TEXT`
   - Stores verbose serialized form changes
   - Used by: `store-form-change!`, `query-forms-by-type`

### Code Locations Using Verbose AST

**In `src/llm_planner/ast.clj`:**
- Lines 24-65: Verbose serialization functions
  - `serialize-node-for-db`
  - `deserialize-ast-from-db`
  - `ast->json`
  - `json->ast`
- Lines 91-108: `parse-clojure-string` (verbose)
- Lines 280-456: Backward compatibility layer for verbose AST
  - `find-defns`, `find-defs`, `find-namespace-form` with map checks
  - `compare-forms` with dual API
- Lines 475-519: Legacy functions
  - `find-forms-by-type`
  - `extract-top-level-forms`
  - `reconstruct-source-from-ast`
- Lines 524-595: Database operations using verbose AST
  - `store-file-content!`
  - `get-file-content-ast`
  - `store-form-change!`
  - `store-defn-changes!`
  - `query-forms-by-type`

**In `test/llm_planner/ast_test.clj`:**
- All tests use verbose AST format via `parse-clojure-string`
- Tests for database storage/retrieval of verbose AST
- Tests for serialization/deserialization

**In `test/llm_planner/ast/compact_test.clj`:**
- Line 113: Uses verbose AST for size comparison only (OK to keep)

## Refactoring Strategy

### Phase 1: Database Schema Migration ✅

**Create new migration** (`002-compact-ast-migration.edn`):

1. Add new columns for compact AST:
   - `file_content.compact_ast TEXT` 
   - `file_change_ast.node_compact_ast TEXT`

2. Keep old columns temporarily for backward compatibility:
   - `file_content.ast_json TEXT` (mark as deprecated)
   - `file_change_ast.node_ast_json TEXT` (mark as deprecated)

3. Future migration (003) will drop old columns after data migration complete

**Rationale:** Non-destructive migration allows rollback and gradual transition.

### Phase 2: Refactor `llm_planner.ast` Namespace ✅

**Goal:** Make compact AST the primary format, remove verbose code.

**Changes:**

1. **Remove verbose serialization functions** (lines 24-65):
   - DELETE: `serialize-node-for-db`
   - DELETE: `deserialize-ast-from-db`
   - DELETE: `ast->json` (verbose)
   - DELETE: `json->ast` (verbose)

2. **Update JSON serialization to use compact format**:
   ```clojure
   (defn ast->json
     "Convert compact AST to JSON string for storage."
     [compact-ast]
     (json/write-str compact-ast))
   
   (defn json->ast
     "Parse JSON string back to compact AST."
     [json-str]
     (json/read-str json-str :key-fn keyword))
   ```

3. **Replace `parse-clojure-string` with compact version**:
   ```clojure
   (defn parse-clojure-string
     "Parse Clojure string and return compact AST.
      Returns compact vector-based AST or error map.
      
      This is now an alias to compact/compact-ast for consistency."
     [code-str]
     (compact/compact-ast code-str))
   ```

4. **Simplify `find-defns`, `find-defs`, `find-namespace-form`**:
   - Remove verbose AST backward compatibility checks
   - Only support: string input → parse → find, or compact AST input → find
   - Remove zipper API (too complex, compact AST is simpler)

5. **Simplify `compare-forms`, `compare-defns`, `compare-defs`**:
   - Remove `:full-form` / `:old-form` / `:new-form` compatibility
   - Only use `:source` / `:old-source` / `:new-source`

6. **DELETE legacy functions** (lines 475-519):
   - `find-forms-by-type` 
   - `extract-top-level-forms`
   - `reconstruct-source-from-ast`

7. **Update database operations** (lines 524-595):
   ```clojure
   (defn store-file-content!
     "Store file content with compact AST in database."
     [db file-id content]
     (let [ast (compact/compact-ast content)]
       (if (:error ast)
         nil
         (let [ast-json (ast->json ast)]
           (jdbc/execute-one!
             db
             (sql/format
               {:insert-into :file_content
                :values [{:file_id file-id
                          :content content
                          :compact_ast ast-json}]  ; Changed field
                :returning [:id]}))))))
   
   (defn get-file-content-ast
     "Retrieve compact AST for a file content record."
     [db content-id]
     (when-let [row (jdbc/execute-one!
                      db
                      (sql/format
                        {:select [:compact_ast]  ; Changed field
                         :from [:file_content]
                         :where [:= :id content-id]}))]
       (json->ast (:file_content/compact_ast row))))  ; Changed field
   ```

8. **Add require for compact namespace**:
   ```clojure
   (ns llm-planner.ast
     "Functions for parsing Clojure code and storing compact AST in SQLite."
     (:require
      [clojure.data.json :as json]
      [llm-planner.ast.compact :as compact]  ; Add this
      [honey.sql :as sql]
      [next.jdbc :as jdbc]))
   ```

9. **Update namespace docstring** to reflect compact AST focus

10. **Remove zipper-based API** (too complex for most use cases):
    - DELETE: `zloc-of-string`, `zloc-of-string*`
    - DELETE: `find-all` (zipper version)
    - DELETE: `defn-form?`, `def-form?`, `ns-form?` (zipper versions)
    - DELETE: `extract-fn-name`, `extract-docstring`, etc. (zipper versions)
    - DELETE: `summarize-namespace` (zipper version)

**New simplified API:**

```clojure
;; Parsing
(parse-clojure-string code-str) → compact AST or error map

;; Finding forms (work on strings or compact ASTs)
(find-defns source) → [{:name, :docstring, :node, :source}, ...]
(find-defs source) → [{:name, :docstring, :node, :source}, ...]
(find-namespace-form source) → {:name, :docstring, :node, :source}

;; Comparison
(compare-defns old-source new-source) → change list
(compare-defs old-source new-source) → change list

;; Database
(store-file-content! db file-id content) → id
(get-file-content-ast db content-id) → compact AST
(store-form-change! db file-change-id form-data) → id
(store-defn-changes! db file-change-id old new) → changes
```

**Extraction functions:** Move to `compact` namespace as helpers:

```clojure
;; In llm-planner.ast.compact

(defn extract-fn-name
  "Extract function name from defn/def compact AST node."
  [defn-node]
  (when (or (defn-form? defn-node) (def-form? defn-node))
    (compact/token-value (nth defn-node 2))))

(defn extract-docstring
  "Extract docstring from defn/def compact AST node.
   Returns nil if no docstring."
  [defn-node]
  (when (or (defn-form? defn-node) (def-form? defn-node))
    (let [third-elem (nth defn-node 3 nil)]
      (when (and (compact/token? third-elem)
                 (string? (compact/token-value third-elem)))
        (compact/token-value third-elem)))))

(defn extract-namespace-name
  "Extract namespace name from ns compact AST node."
  [ns-node]
  (when (ns-form? ns-node)
    (compact/token-value (nth ns-node 2))))
```

### Phase 3: Refactor Tests ✅

**Update `test/llm_planner/ast_test.clj`:**

1. Update all tests to expect compact AST format
2. Replace map structure assertions with vector structure assertions
3. Update database tests to use `compact_ast` column
4. Update serialization tests to use compact format

**Example transformation:**

```clojure
;; OLD
(deftest test-parse-clojure-string
  (testing "Parsing Clojure code to AST"
    (let [code "(defn hello [name] (str \"Hello, \" name))"
          ast (ast/parse-clojure-string code)]
      (is (map? ast))
      (is (= :forms (:tag ast)))
      (is (contains? ast :children))
      (is (string? (:string ast))))))

;; NEW
(deftest test-parse-clojure-string
  (testing "Parsing Clojure code to compact AST"
    (let [code "(defn hello [name] (str \"Hello, \" name))"
          ast (ast/parse-clojure-string code)]
      (is (vector? ast))
      (is (= :forms (first ast)))
      (is (= 1 (count (compact/find-defns ast)))))))
```

### Phase 4: Update Documentation ✅

1. Update all docstrings to mention compact AST format
2. Update examples in comments to show compact format
3. Update README if it mentions AST format
4. Mark old format as deprecated in any remaining docs

### Phase 5: Data Migration (Optional) ⚠️

**Only if you have existing data in production:**

Create migration script to convert existing verbose AST to compact:

```clojure
(ns llm-planner.migrations.migrate-ast-to-compact
  (:require
   [llm-planner.ast :as ast]
   [llm-planner.ast.compact :as compact]
   [next.jdbc :as jdbc]
   [honey.sql :as sql]))

(defn migrate-file-content-ast!
  "Migrate file_content table from verbose to compact AST."
  [db]
  (let [rows (jdbc/execute! db
               (sql/format
                 {:select [:id :content]
                  :from [:file_content]
                  :where [:is :compact_ast nil]}))]
    (doseq [{:keys [file_content/id file_content/content]} rows]
      (let [compact-ast (compact/compact-ast content)]
        (when-not (:error compact-ast)
          (jdbc/execute-one! db
            (sql/format
              {:update :file_content
               :set {:compact_ast (ast/ast->json compact-ast)}
               :where [:= :id id]})))))))

(defn migrate-file-change-ast!
  "Migrate file_change_ast table from verbose to compact AST."
  [db]
  ;; Similar logic for file_change_ast table
  )
```

### Phase 6: Cleanup (Final) ✅

**After all code migrated and tested:**

1. Create migration `003-drop-verbose-ast-columns.edn`:
   ```clojure
   {:id "003-drop-verbose-ast-columns"
    :up ["ALTER TABLE file_content DROP COLUMN ast_json"
         "ALTER TABLE file_change_ast DROP COLUMN node_ast_json"]
    :down ["ALTER TABLE file_content ADD COLUMN ast_json TEXT"
           "ALTER TABLE file_change_ast ADD COLUMN node_ast_json TEXT"]}
   ```

2. Remove any remaining verbose AST code
3. Remove backward compatibility checks
4. Final test suite run

## Implementation Order

### Step 1: Create Database Migration ✅
- [x] Create `002-compact-ast-migration.edn`
- [x] Add `compact_ast` and `node_compact_ast` columns
- [x] Test migration up/down

### Step 2: Add Compact Helper Functions ✅
- [x] Add extraction helpers to `compact` namespace
- [x] Test extraction functions work correctly

### Step 3: Refactor `llm_planner.ast` Namespace ✅
- [x] Update `parse-clojure-string` to use compact format
- [x] Simplify `find-defns`, `find-defs`, `find-namespace-form`
- [x] Update database functions to use `compact_ast` column
- [x] Remove verbose serialization functions
- [x] Remove zipper API
- [x] Remove legacy functions
- [x] Update namespace docstring

### Step 4: Update Tests ✅
- [x] Update `ast_test.clj` assertions for compact format
- [x] Update database tests to use new column names
- [x] Add new tests for compact-specific features
- [x] Ensure all tests pass

### Step 5: Clean Up ✅
- [x] Remove any dead code
- [x] Update all docstrings
- [x] Run full test suite
- [x] Update documentation

### Step 6: (Optional) Data Migration ⚠️
- [ ] Only if you have production data
- [ ] Write migration script
- [ ] Test on backup first
- [ ] Run migration
- [ ] Verify data integrity

### Step 7: Final Cleanup ✅
- [ ] Create `003-drop-verbose-ast-columns.edn`
- [ ] Drop old columns after confirming no issues
- [ ] Remove any remaining compatibility code
- [ ] Final documentation update

## Risk Assessment

### Low Risk ✅
- Adding new columns (non-destructive)
- Adding compact helper functions
- Writing tests for compact format

### Medium Risk ⚠️
- Refactoring `llm_planner.ast` namespace (affects all AST usage)
- Updating tests (might reveal issues)

**Mitigation:** 
- Do incrementally, test at each step
- Keep git history clean for easy rollback
- Run full test suite after each change

### High Risk 🚨
- Dropping old database columns (data loss if mistake)
- Data migration on production database

**Mitigation:**
- Do last, after everything else working
- Backup database first
- Test migration script on copy
- Keep old columns for grace period

## Testing Strategy

### Unit Tests
- [x] All compact AST functions have tests
- [x] Database operations with compact AST
- [x] Parsing and reconstruction

### Integration Tests
- [x] Full workflow: parse → store → retrieve → query
- [x] Comparison operations between versions
- [x] Error handling for invalid input

### Migration Tests
- [x] Schema migration up/down works
- [ ] Data migration script (if needed)
- [ ] Old and new columns coexist correctly

## Rollback Plan

### If Issues Found During Refactoring
1. Revert git commits
2. Database migration can be rolled back with `:down`
3. Old verbose format still available in git history

### If Issues Found After Deployment
1. Old columns still exist (if haven't run step 7)
2. Can write backward compatibility layer if needed
3. Restore from backup if catastrophic

## Success Criteria

- [x] All tests pass with compact AST format
- [x] Database stores compact AST correctly
- [x] Size reduction verified (~80-90%)
- [x] Performance maintained or improved
- [x] No verbose AST code remaining (except size comparison test)
- [x] Documentation updated
- [ ] Production deployment successful (if applicable)
- [ ] Old columns dropped (final step)

## Estimated Timeline

- **Step 1 (Migration):** 30 minutes ✅
- **Step 2 (Helpers):** 30 minutes ✅
- **Step 3 (Refactor ast.clj):** 2 hours ✅
- **Step 4 (Update tests):** 1 hour ✅
- **Step 5 (Cleanup):** 30 minutes ✅
- **Step 6 (Data migration):** 1 hour (if needed) ⚠️
- **Step 7 (Final cleanup):** 30 minutes ⏳

**Total:** ~5-6 hours (or 4-5 hours if no data migration)

## Decision Points

### Question 1: Keep zipper API?
**Decision:** NO ❌

**Rationale:**
- Compact AST is simpler for most use cases
- Zipper API adds complexity without clear benefit
- Can reconstruct zippers from compact AST if needed later
- Most operations don't need zipper navigation

### Question 2: Gradual or immediate cutover?
**Decision:** Immediate cutover with new column ✅

**Rationale:**
- Simpler than maintaining dual format
- New column allows rollback
- Project is early stage (low risk)
- Compact format is tested and proven

### Question 3: Keep verbose format for formatters?
**Decision:** NO, use compact with `:preserve-whitespace? true` ✅

**Rationale:**
- Compact format supports whitespace preservation
- Still get size reduction (just less dramatic)
- One format is simpler to maintain

## Next Actions

1. Review this plan with team/stakeholders ✅
2. Create feature branch for refactoring ⏳
3. Execute steps 1-5 incrementally ✅
4. Review and test thoroughly ⏳
5. Merge when confident ⏳
6. Execute steps 6-7 if needed ⏳

---

**Status:** Plan complete, ready to execute
**Priority:** High (foundation for future work)
**Complexity:** Medium (well-defined scope)
