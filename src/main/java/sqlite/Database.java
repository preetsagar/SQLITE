package sqlite;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/** Reads the SQLite file format: header, pages, b-tree traversal, records. */
public final class Database implements AutoCloseable {
    public final int pageSize;
    private final RandomAccessFile file;

    public Database(String path) throws IOException {
        this.file = new RandomAccessFile(path, "r");
        byte[] header = new byte[100];
        file.readFully(header);
        int ps = ((header[16] & 0xff) << 8) | (header[17] & 0xff);
        this.pageSize = (ps == 1) ? 65536 : ps;
    }

    @Override
    public void close() throws IOException {
        file.close();
    }

    public byte[] readPage(long pageNumber) {
        byte[] page = new byte[pageSize];
        try {
            file.seek((pageNumber - 1) * (long) pageSize);
            file.readFully(page);
        } catch (IOException e) {
            throw new RuntimeException("failed reading page " + pageNumber, e);
        }
        return page;
    }

    // ---- schema (sqlite_schema, rootpage 1) ----

    public record SchemaRow(String type, String name, String tblName, long rootPage, String sql) {}

    public List<SchemaRow> schema() {
        List<SchemaRow> rows = new ArrayList<>();
        scanTable(1, (rowid, values) -> {
            rows.add(new SchemaRow(
                    str(values.get(0)),
                    str(values.get(1)),
                    str(values.get(2)),
                    ((Number) values.get(3)).longValue(),
                    str(values.get(4))));
        });
        return rows;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    // ---- b-tree traversal ----

    public interface RowVisitor {
        void row(long rowid, List<Object> values);
    }

    public void scanTable(long pageNumber, RowVisitor visitor) {
        byte[] page = readPage(pageNumber);
        int hdr = (pageNumber == 1) ? 100 : 0;
        int type = page[hdr] & 0xff;
        int numCells = u16(page, hdr + 3);
        int ptrArray = hdr + (isInterior(type) ? 12 : 8);

        if (type == 0x0d) { // leaf table
            for (int i = 0; i < numCells; i++) {
                int cell = u16(page, ptrArray + 2 * i);
                Varint payloadSize = Varint.read(page, cell);
                Varint rowid = Varint.read(page, cell + payloadSize.size);
                int payloadStart = cell + payloadSize.size + rowid.size;
                byte[] payload = readPayload(page, payloadStart, (int) payloadSize.value, false);
                visitor.row(rowid.value, parseRecord(payload));
            }
        } else if (type == 0x05) { // interior table
            for (int i = 0; i < numCells; i++) {
                int cell = u16(page, ptrArray + 2 * i);
                long child = u32(page, cell);
                scanTable(child, visitor);
            }
            scanTable(u32(page, hdr + 8), visitor);
        } else {
            throw new IllegalStateException("not a table b-tree page: type " + type);
        }
    }

    /** Look up a single row in a table b-tree by rowid. Returns null if absent. */
    public List<Object> findRow(long pageNumber, long targetRowid) {
        byte[] page = readPage(pageNumber);
        int hdr = (pageNumber == 1) ? 100 : 0;
        int type = page[hdr] & 0xff;
        int numCells = u16(page, hdr + 3);
        int ptrArray = hdr + (isInterior(type) ? 12 : 8);

        if (type == 0x0d) {
            for (int i = 0; i < numCells; i++) {
                int cell = u16(page, ptrArray + 2 * i);
                Varint payloadSize = Varint.read(page, cell);
                Varint rowid = Varint.read(page, cell + payloadSize.size);
                if (rowid.value == targetRowid) {
                    int payloadStart = cell + payloadSize.size + rowid.size;
                    byte[] payload = readPayload(page, payloadStart, (int) payloadSize.value, false);
                    return parseRecord(payload);
                }
            }
            return null;
        } else if (type == 0x05) {
            for (int i = 0; i < numCells; i++) {
                int cell = u16(page, ptrArray + 2 * i);
                long child = u32(page, cell);
                Varint key = Varint.read(page, cell + 4);
                if (targetRowid <= key.value) {
                    return findRow(child, targetRowid);
                }
            }
            return findRow(u32(page, hdr + 8), targetRowid);
        }
        throw new IllegalStateException("not a table b-tree page: type " + type);
    }

    /**
     * Walk an index b-tree collecting rowids whose first indexed column equals {@code target}
     * (BINARY collation). The index key layout is (indexed columns..., rowid).
     */
    public void searchIndex(long pageNumber, String target, List<Long> out) {
        byte[] targetBytes = target.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        searchIndex(pageNumber, targetBytes, out);
    }

    private void searchIndex(long pageNumber, byte[] target, List<Long> out) {
        byte[] page = readPage(pageNumber);
        int hdr = (pageNumber == 1) ? 100 : 0;
        int type = page[hdr] & 0xff;
        int numCells = u16(page, hdr + 3);
        int ptrArray = hdr + (isInterior(type) ? 12 : 8);

        if (type == 0x0a) { // leaf index
            for (int i = 0; i < numCells; i++) {
                int cell = u16(page, ptrArray + 2 * i);
                Varint payloadSize = Varint.read(page, cell);
                byte[] payload = readPayload(page, cell + payloadSize.size, (int) payloadSize.value, true);
                List<Object> rec = parseRecord(payload);
                int cmp = compare(target, rec.get(0));
                if (cmp == 0) {
                    out.add(((Number) rec.get(rec.size() - 1)).longValue());
                } else if (cmp < 0) {
                    return;
                }
            }
        } else if (type == 0x02) { // interior index
            for (int i = 0; i < numCells; i++) {
                int cell = u16(page, ptrArray + 2 * i);
                long child = u32(page, cell);
                Varint payloadSize = Varint.read(page, cell + 4);
                byte[] payload = readPayload(page, cell + 4 + payloadSize.size, (int) payloadSize.value, true);
                List<Object> rec = parseRecord(payload);
                int cmp = compare(target, rec.get(0));
                if (cmp <= 0) {
                    searchIndex(child, target, out);
                }
                if (cmp == 0) {
                    out.add(((Number) rec.get(rec.size() - 1)).longValue());
                }
                if (cmp < 0) {
                    return;
                }
            }
            searchIndex(u32(page, hdr + 8), target, out);
        } else {
            throw new IllegalStateException("not an index b-tree page: type " + type);
        }
    }

    private static int compare(byte[] target, Object field) {
        byte[] b = (field == null)
                ? new byte[0]
                : field.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int n = Math.min(target.length, b.length);
        for (int i = 0; i < n; i++) {
            int d = (target[i] & 0xff) - (b[i] & 0xff);
            if (d != 0) return d;
        }
        return target.length - b.length;
    }

    // ---- payload / overflow ----

    private byte[] readPayload(byte[] page, int start, int payloadSize, boolean isIndex) {
        int usable = pageSize; // reserved-space region is 0 for databases we handle
        int maxLocal = isIndex ? ((usable - 12) * 64 / 255) - 23 : usable - 35;
        if (payloadSize <= maxLocal) {
            byte[] out = new byte[payloadSize];
            System.arraycopy(page, start, out, 0, payloadSize);
            return out;
        }

        int minLocal = ((usable - 12) * 32 / 255) - 23;
        int k = minLocal + ((payloadSize - minLocal) % (usable - 4));
        int local = (k <= maxLocal) ? k : minLocal;

        byte[] out = new byte[payloadSize];
        System.arraycopy(page, start, out, 0, local);
        long overflow = u32(page, start + local);
        int filled = local;
        while (overflow != 0 && filled < payloadSize) {
            byte[] ov = readPage(overflow);
            overflow = u32(ov, 0);
            int chunk = Math.min(usable - 4, payloadSize - filled);
            System.arraycopy(ov, 4, out, filled, chunk);
            filled += chunk;
        }
        return out;
    }

    // ---- record format ----

    public static List<Object> parseRecord(byte[] payload) {
        Varint headerSize = Varint.read(payload, 0);
        int p = headerSize.size;
        List<Long> serialTypes = new ArrayList<>();
        while (p < headerSize.value) {
            Varint st = Varint.read(payload, p);
            serialTypes.add(st.value);
            p += st.size;
        }
        List<Object> values = new ArrayList<>(serialTypes.size());
        int body = (int) headerSize.value;
        for (long st : serialTypes) {
            if (st == 0) {
                values.add(null);
            } else if (st >= 1 && st <= 6) {
                int len = switch ((int) st) {
                    case 5 -> 6;
                    case 6 -> 8;
                    default -> (int) st;
                };
                values.add(readSignedInt(payload, body, len));
                body += len;
            } else if (st == 7) {
                values.add(Double.longBitsToDouble(readSignedInt(payload, body, 8)));
                body += 8;
            } else if (st == 8) {
                values.add(0L);
            } else if (st == 9) {
                values.add(1L);
            } else if (st >= 12) {
                int len = (int) ((st - (st % 2 == 0 ? 12 : 13)) / 2);
                byte[] slice = new byte[len];
                System.arraycopy(payload, body, slice, 0, len);
                body += len;
                if (st % 2 == 0) {
                    values.add(slice);
                } else {
                    values.add(new String(slice, java.nio.charset.StandardCharsets.UTF_8));
                }
            } else {
                values.add(null); // reserved 10, 11
            }
        }
        return values;
    }

    private static long readSignedInt(byte[] buf, int off, int len) {
        long v = 0;
        for (int i = 0; i < len; i++) {
            v = (v << 8) | (buf[off + i] & 0xff);
        }
        if (len < 8) {
            long signBit = 1L << (len * 8 - 1);
            if ((v & signBit) != 0) {
                v -= (1L << (len * 8));
            }
        }
        return v;
    }

    // ---- low-level helpers ----

    private static boolean isInterior(int pageType) {
        return pageType == 0x02 || pageType == 0x05;
    }

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
    }

    private static long u32(byte[] b, int off) {
        return ((long) (b[off] & 0xff) << 24)
                | ((b[off + 1] & 0xff) << 16)
                | ((b[off + 2] & 0xff) << 8)
                | (b[off + 3] & 0xff);
    }
}
