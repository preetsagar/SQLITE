package sqlite;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal parsing for the SQL subset the challenge exercises. */
public final class Sql {

    public record Column(String name, boolean rowidAlias) {}

    /** Parse the column definitions out of a {@code CREATE TABLE} statement. */
    public static List<Column> parseColumns(String createTableSql) {
        int open = createTableSql.indexOf('(');
        int close = createTableSql.lastIndexOf(')');
        String body = createTableSql.substring(open + 1, close);

        List<String> parts = splitTopLevel(body);
        List<Column> columns = new ArrayList<>();
        for (String part : parts) {
            String def = part.trim();
            if (def.isEmpty()) continue;
            String upper = def.toUpperCase();
            if (upper.startsWith("PRIMARY ") || upper.startsWith("UNIQUE ") || upper.startsWith("UNIQUE(")
                    || upper.startsWith("CHECK") || upper.startsWith("FOREIGN ") || upper.startsWith("CONSTRAINT ")) {
                continue; // table-level constraint, not a column
            }
            String name = firstIdentifier(def);
            String rest = def.substring(rawIdentifierLength(def)).toUpperCase();
            boolean rowidAlias = rest.contains("INTEGER") && rest.contains("PRIMARY KEY");
            columns.add(new Column(name, rowidAlias));
        }
        return columns;
    }

    public record Select(List<String> columns, boolean count, String table, String whereColumn, String whereValue) {}

    private static final Pattern SELECT = Pattern.compile(
            "(?is)^\\s*select\\s+(.+?)\\s+from\\s+([\\w\"'`\\[\\]]+)" +
                    "(?:\\s+where\\s+([\\w\"'`\\[\\]]+)\\s*=\\s*'([^']*)')?\\s*;?\\s*$");

    public static Select parseSelect(String query) {
        Matcher m = SELECT.matcher(query);
        if (!m.matches()) {
            throw new IllegalArgumentException("unsupported query: " + query);
        }
        String colsPart = m.group(1).trim();
        boolean count = colsPart.replaceAll("\\s+", "").equalsIgnoreCase("count(*)");
        List<String> columns = new ArrayList<>();
        if (!count) {
            for (String c : splitTopLevel(colsPart)) {
                columns.add(unquote(c.trim()));
            }
        }
        String table = unquote(m.group(2));
        String whereColumn = m.group(3) == null ? null : unquote(m.group(3));
        String whereValue = m.group(4);
        return new Select(columns, count, table, whereColumn, whereValue);
    }

    /** Table name an index covers, from {@code CREATE INDEX ... ON <table> (<col>, ...)}. */
    private static final Pattern CREATE_INDEX = Pattern.compile(
            "(?is)on\\s+([\\w\"'`\\[\\]]+)\\s*\\(([^)]*)\\)");

    public static String indexTable(String createIndexSql) {
        Matcher m = CREATE_INDEX.matcher(createIndexSql);
        return m.find() ? unquote(m.group(1)) : null;
    }

    public static List<String> indexColumns(String createIndexSql) {
        Matcher m = CREATE_INDEX.matcher(createIndexSql);
        List<String> cols = new ArrayList<>();
        if (m.find()) {
            for (String c : m.group(2).split(",")) {
                cols.add(unquote(c.trim()));
            }
        }
        return cols;
    }

    // ---- helpers ----

    private static List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                cur.append(c);
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'' || c == '`') {
                quote = c;
                cur.append(c);
            } else if (c == '(') {
                depth++;
                cur.append(c);
            } else if (c == ')') {
                depth--;
                cur.append(c);
            } else if (c == ',' && depth == 0) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static String firstIdentifier(String def) {
        return unquote(def.substring(0, rawIdentifierLength(def)));
    }

    private static int rawIdentifierLength(String def) {
        if (def.isEmpty()) return 0;
        char c = def.charAt(0);
        if (c == '"' || c == '\'' || c == '`') {
            int end = def.indexOf(c, 1);
            return end < 0 ? def.length() : end + 1;
        }
        if (c == '[') {
            int end = def.indexOf(']', 1);
            return end < 0 ? def.length() : end + 1;
        }
        int i = 0;
        while (i < def.length() && !Character.isWhitespace(def.charAt(i)) && def.charAt(i) != '(') i++;
        return i;
    }

    private static String unquote(String s) {
        if (s.length() >= 2) {
            char f = s.charAt(0), l = s.charAt(s.length() - 1);
            if ((f == '"' && l == '"') || (f == '\'' && l == '\'') || (f == '`' && l == '`')
                    || (f == '[' && l == ']')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    private Sql() {}
}
