package sqlite;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Executes the supported dot-commands and SELECT queries against a {@link Database}. */
public final class Engine {
    private final Database db;
    private final List<Database.SchemaRow> schema;

    public Engine(Database db) {
        this.db = db;
        this.schema = db.schema();
    }

    public void run(String command) {
        if (command.equals(".dbinfo")) {
            dbinfo();
        } else if (command.equals(".tables")) {
            tables();
        } else {
            select(Sql.parseSelect(command));
        }
    }

    private void dbinfo() {
        long tableCount = schema.stream()
                .filter(r -> r.type().equals("table") && !r.name().startsWith("sqlite_"))
                .count();
        System.out.println("database page size: " + db.pageSize);
        System.out.println("number of tables: " + tableCount);
    }

    private void tables() {
        List<String> names = new ArrayList<>();
        for (Database.SchemaRow r : schema) {
            if (r.type().equals("table") && !r.name().startsWith("sqlite_")) {
                names.add(r.name());
            }
        }
        names.sort(String::compareTo);
        System.out.println(String.join(" ", names));
    }

    private void select(Sql.Select q) {
        Database.SchemaRow table = findTable(q.table());
        if (table == null) {
            throw new IllegalArgumentException("no such table: " + q.table());
        }
        List<Sql.Column> columns = Sql.parseColumns(table.sql());

        if (q.count() && q.whereColumn() == null) {
            long[] n = {0};
            db.scanTable(table.rootPage(), (rowid, values) -> n[0]++);
            System.out.println(n[0]);
            return;
        }

        int whereIdx = q.whereColumn() == null ? -1 : columnIndex(columns, q.whereColumn());
        List<Integer> selectIdx = new ArrayList<>();
        for (String c : q.columns()) {
            selectIdx.add(columnIndex(columns, c));
        }

        List<String> lines = new ArrayList<>();
        Database.RowVisitor emit = (rowid, values) -> {
            if (whereIdx >= 0 && !matches(cell(columns, whereIdx, rowid, values), q.whereValue())) {
                return;
            }
            List<String> out = new ArrayList<>(selectIdx.size());
            for (int idx : selectIdx) {
                out.add(render(cell(columns, idx, rowid, values)));
            }
            lines.add(String.join("|", out));
        };

        Database.SchemaRow index = q.whereColumn() == null ? null : findIndex(q.table(), q.whereColumn());
        if (index != null) {
            List<Long> rowids = new ArrayList<>();
            db.searchIndex(index.rootPage(), q.whereValue(), rowids);
            for (long rowid : rowids) {
                List<Object> values = db.findRow(table.rootPage(), rowid);
                if (values != null) {
                    emit.row(rowid, values);
                }
            }
        } else {
            db.scanTable(table.rootPage(), emit);
        }

        if (q.count()) {
            System.out.println(lines.size());
        } else {
            lines.forEach(System.out::println);
        }
    }

    private static Object cell(List<Sql.Column> columns, int idx, long rowid, List<Object> values) {
        if (columns.get(idx).rowidAlias()) {
            return rowid;
        }
        return idx < values.size() ? values.get(idx) : null;
    }

    private static boolean matches(Object cell, String target) {
        return cell != null && cell.toString().equals(target);
    }

    private static String render(Object cell) {
        return cell == null ? "" : cell.toString();
    }

    private Database.SchemaRow findTable(String name) {
        for (Database.SchemaRow r : schema) {
            if (r.type().equals("table") && r.name().equalsIgnoreCase(name)) {
                return r;
            }
        }
        return null;
    }

    private Database.SchemaRow findIndex(String table, String column) {
        for (Database.SchemaRow r : schema) {
            if (!r.type().equals("index") || r.sql() == null) continue;
            String idxTable = Sql.indexTable(r.sql());
            List<String> idxCols = Sql.indexColumns(r.sql());
            if (idxTable != null && idxTable.equalsIgnoreCase(table)
                    && !idxCols.isEmpty() && idxCols.get(0).equalsIgnoreCase(column)) {
                return r;
            }
        }
        return null;
    }

    private static int columnIndex(List<Sql.Column> columns, String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equalsIgnoreCase(name)) {
                return i;
            }
        }
        throw new IllegalArgumentException("no such column: " + name);
    }

    public static void main(String databaseFilePath, String command) throws IOException {
        try (Database db = new Database(databaseFilePath)) {
            new Engine(db).run(command);
        }
    }

    static { Locale.setDefault(Locale.ROOT); }
}
