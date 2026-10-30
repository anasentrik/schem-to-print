package dev.schemprinter;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** NBT reader mini (sengaja gak pakai API NBT Minecraft biar gak kena perubahan API antar versi). */
final class Nbt {
    private Nbt() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> readFile(Path path) throws IOException {
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(path))) {
            raw.mark(2);
            int b0 = raw.read(), b1 = raw.read();
            raw.reset();
            boolean gzip = b0 == 0x1f && b1 == 0x8b;
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(gzip ? new GZIPInputStream(raw) : raw))) {
                int type = in.readUnsignedByte();
                if (type != 10) throw new IOException("Bukan file NBT (root harus compound).");
                in.readUTF();
                return (Map<String, Object>) readPayload(in, 10);
            }
        }
    }

    private static Object readPayload(DataInputStream in, int type) throws IOException {
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: {
                byte[] b = new byte[in.readInt()];
                in.readFully(b);
                return b;
            }
            case 8: return in.readUTF();
            case 9: {
                int t = in.readUnsignedByte();
                int n = in.readInt();
                List<Object> list = new ArrayList<>(Math.max(0, Math.min(n, 4096)));
                for (int i = 0; i < n; i++) list.add(readPayload(in, t));
                return list;
            }
            case 10: {
                Map<String, Object> map = new LinkedHashMap<>();
                while (true) {
                    int t = in.readUnsignedByte();
                    if (t == 0) break;
                    String key = in.readUTF();
                    map.put(key, readPayload(in, t));
                }
                return map;
            }
            case 11: {
                int[] a = new int[in.readInt()];
                for (int i = 0; i < a.length; i++) a[i] = in.readInt();
                return a;
            }
            case 12: {
                long[] a = new long[in.readInt()];
                for (int i = 0; i < a.length; i++) a[i] = in.readLong();
                return a;
            }
            default: throw new IOException("Tipe NBT gak dikenal: " + type);
        }
    }
}
