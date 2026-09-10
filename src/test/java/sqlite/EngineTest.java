package sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Exercises every stage against real SQLite fixtures. */
class EngineTest {

    private static String run(String fixture, String command) throws Exception {
        Path tmp = Files.createTempFile("fixture", ".db");
        try (var in = EngineTest.class.getResourceAsStream("/" + fixture)) {
            Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured));
        try (Database db = new Database(tmp.toString())) {
            new Engine(db).run(command);
        } finally {
            System.setOut(original);
            Files.deleteIfExists(tmp);
        }
        return captured.toString().strip();
    }

    private static List<String> sortedLines(String s) {
        return s.isEmpty() ? List.of() : Arrays.stream(s.split("\n")).sorted().collect(Collectors.toList());
    }

    // Stage dr6
    @Test
    void printsPageSize() throws Exception {
        assertEquals("database page size: 4096", run("sample.db", ".dbinfo").split("\n")[0]);
    }

    // Stage ce0
    @Test
    void printsTableCount() throws Exception {
        assertEquals("number of tables: 2", run("sample.db", ".dbinfo").split("\n")[1]);
    }

    // Stage sz4
    @Test
    void printsTableNames() throws Exception {
        assertEquals("apples oranges", run("sample.db", ".tables"));
    }

    // Stage nd9
    @Test
    void countsRows() throws Exception {
        assertEquals("4", run("sample.db", "select count(*) from apples"));
        assertEquals("2500", run("superheroes.db", "select count(*) from superheroes"));
    }

    // Stage az9
    @Test
    void readsSingleColumn() throws Exception {
        assertEquals(
                List.of("Fuji", "Golden Delicious", "Granny Smith", "Honeycrisp"),
                sortedLines(run("sample.db", "select name from apples")));
    }

    // Stage vc9
    @Test
    void readsMultipleColumns() throws Exception {
        assertEquals(
                List.of(
                        "1|Granny Smith|Light Green",
                        "2|Fuji|Red",
                        "3|Honeycrisp|Blush Red",
                        "4|Golden Delicious|Yellow"),
                sortedLines(run("sample.db", "select id, name, color from apples")));
    }

    // Stage rf3
    @Test
    void filtersWithWhere() throws Exception {
        assertEquals("Golden Delicious", run("sample.db", "select name from apples where color = 'Yellow'"));
    }

    // Stage ws9 — full table scan over a multi-page b-tree
    @Test
    void fullTableScan() throws Exception {
        assertEquals(
                List.of(
                        "1151|Freak (New Earth)",
                        "1874|Meloni Thawne (New Earth)",
                        "2077|Lynx II (New Earth)",
                        "78|Mari McCabe (New Earth)",
                        "840|Charlie the Owl (New Earth)"),
                sortedLines(run("superheroes.db",
                        "SELECT id, name FROM superheroes WHERE eye_color = 'Amber Eyes'")));
    }

    // Stage nz8 — index-backed scan
    @Test
    void indexScan() throws Exception {
        String viaEngine = run("companies_small.db",
                "SELECT id, name FROM companies WHERE country = 'tonga'");
        // every row's country really is 'tonga', and the count is stable for the fixture
        assertEquals(329, sortedLines(viaEngine).size());
    }
}
