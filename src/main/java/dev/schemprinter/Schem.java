package dev.schemprinter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

/** Isi file .schem (Sponge Schematic v2 / v3). ids[(y * l + z) * w + x] = index ke palette. */
final class Schem {
    final int w, h, l;
    final char[] ids;
    final String[] palette;

    Schem(int w, int h, int l, char[] ids, String[] palette) {
        this.w = w;
        this.h = h;
        this.l = l;
        this.ids = ids;
        this.palette = palette;
    }

    @SuppressWarnings("unchecked")
    static Schem load(Path path) throws IOException {
        Map<String, Object> root = Nbt.readFile(path);
        if (root.get("Schematic") instanceof Map<?, ?> inner) root = (Map<String, Object>) inner;   // v3

        int w = dim(root.get("Width")), h = dim(root.get("Height")), l = dim(root.get("Length"));

        Map<String, Object> palTag;
        byte[] data;
        if (root.get("Blocks") instanceof Map<?, ?> blocks) {                                       // v3
            palTag = (Map<String, Object>) blocks.get("Palette");
            data = (byte[]) blocks.get("Data");
        } else {                                                                                    // v2
            palTag = (Map<String, Object>) root.get("Palette");
            data = (byte[]) root.get("BlockData");
        }
        if (palTag == null || data == null)
            throw new IOException("Format .schem gak dikenali (butuh Sponge Schematic v2/v3 dari WorldEdit).");

        int max = 0;
        for (Object v : palTag.values()) max = Math.max(max, ((Number) v).intValue());
        if (max > 0xFFFF) throw new IOException("Palette terlalu besar (>65535).");
        String[] palette = new String[max + 1];
        Arrays.fill(palette, "minecraft:air");
        for (Map.Entry<String, Object> e : palTag.entrySet())
            palette[((Number) e.getValue()).intValue()] = e.getKey();

        long total = (long) w * h * l;
        if (total <= 0 || total > Integer.MAX_VALUE - 16) throw new IOException("Ukuran schematic gak valid: " + total);
        char[] ids = new char[(int) total];

        int n = 0, i = 0;
        while (i < data.length) {                    // varint
            int v = 0, shift = 0;
            while (true) {
                int b = data[i++] & 0xFF;
                v |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            if (n < ids.length) ids[n] = (char) v;
            n++;
        }
        if (n != ids.length) throw new IOException("Ukuran data gak cocok: " + n + " vs " + ids.length);
        return new Schem(w, h, l, ids, palette);
    }

    private static int dim(Object o) throws IOException {
        if (o == null) throw new IOException("Width/Height/Length gak ada.");
        if (o instanceof Short s) return s & 0xFFFF;     // dimensi disimpan sebagai unsigned short
        return ((Number) o).intValue();
    }

    /** Putar 90 derajat searah jarum jam (dilihat dari atas). Lebar dan panjang tertukar. */
    Schem rotateCw() {
        int nw = l, nl = w;
        char[] n = new char[ids.length];
        for (int y = 0; y < h; y++)
            for (int z = 0; z < l; z++)
                for (int x = 0; x < w; x++)
                    n[(y * nl + x) * nw + (l - 1 - z)] = ids[(y * l + z) * w + x];
        return new Schem(nw, h, nl, n, palette);
    }
}
