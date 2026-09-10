package sqlite;

/** SQLite big-endian base-128 varint. */
public final class Varint {
    public long value;
    public int size;

    public static Varint read(byte[] buf, int off) {
        Varint v = new Varint();
        long result = 0;
        for (int i = 0; i < 9; i++) {
            int c = buf[off + i] & 0xff;
            if (i == 8) {
                result = (result << 8) | c;
                v.size = 9;
                v.value = result;
                return v;
            }
            result = (result << 7) | (c & 0x7f);
            if ((c & 0x80) == 0) {
                v.size = i + 1;
                v.value = result;
                return v;
            }
        }
        v.size = 9;
        v.value = result;
        return v;
    }

    private Varint() {}
}
