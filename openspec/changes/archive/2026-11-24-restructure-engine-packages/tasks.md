# Tasks

## 1. Move production classes

- [x] 1.1 Create a branch from `console/foundation` and verify `mvn test` in `engine/` passes before any change, recording the test count as the baseline
- [x] 1.2 Move each production class with `git mv` into its package from design.md and rewrite its `package` line and the imports of moved classes, and verify `git status` shows only renames and every class from the table is in its package
- [x] 1.3 Add `public` to the classes, constructors, methods and fields that `mvn -q compile` reports as not visible, and verify `mvn -q compile` succeeds with no member widened that the compiler did not ask for

## 2. Move tests

- [x] 2.1 Move each test and test fake with `git mv` into its package from design.md, rewrite `package` and imports, and verify `git status` shows only renames
- [x] 2.2 Fix the remaining test compile errors, preferring a change in the test over widening production code, and verify `mvn test` in `engine/` passes with the same test count as the baseline in 1.1

## 3. Docs and end-to-end check

- [x] 3.1 Update `CLAUDE.md` with a short description of the engine's packages, and verify no file outside `openspec/changes/archive/` still describes the classes as living directly in `integrationmapper.engine`
- [x] 3.2 Rebuild the Patchbay container from PathchbayTestAndMock (`docker compose up -d --build --wait`) and run its `mvn test`, and verify all 196 tests pass
- [x] 3.3 Commit the moves and access fixes separately from the `CLAUDE.md` change, and verify `git log --follow` on a moved file shows its history from before the move

## Workflow follow-up

- Archive the change after review.
