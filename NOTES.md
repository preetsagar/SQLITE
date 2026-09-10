# Working notes

## Build / test
- Build + package: `mvn -q -B package -Ddir=/tmp/codecrafters-build-sqlite-java`
- Run: `./your_program.sh <db> "<command>"`
- Unit tests: `mvn -B test` (fixtures in `src/test/resources/`, must stay < 1 MB for the codecrafters remote)

## Remotes
- `origin` → github.com:preetsagar/SQLITE.git (branch `main`)
- `codecrafters` → git.codecrafters.io/... (branch `master`); push with `git push codecrafters main:master`
- A codecrafters push runs the tester remotely and can take minutes; "Mark step as complete" in the output = stage passed.

## Tester source
github.com/codecrafters-io/sqlite-tester — `internal/stage_*.go`

## Stage progress
All 9 stages implemented in one pass:
dr6 page size · ce0 table count · sz4 table names · nd9 row count · az9 single column ·
vc9 multiple columns · rf3 WHERE · ws9 full table scan · nz8 index scan.

## Layout
- `sqlite/Varint` — base-128 varint
- `sqlite/Database` — file header, page reads, record parsing, overflow, b-tree table scan / rowid lookup / index search
- `sqlite/Sql` — CREATE TABLE column parsing, SELECT parsing, CREATE INDEX parsing
- `sqlite/Engine` — dispatches `.dbinfo` / `.tables` / SELECT, picks index vs full scan
