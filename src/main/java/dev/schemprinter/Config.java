package dev.schemprinter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

final class Config {
    static int blocksPerTick = 40;      // kecepatan mode print (20 tick = 1 detik)
    static int distance = 3;            // jarak player ke tepi patung
    static String face = "south";       // sisi schematic yang jadi MUKA patung
    static int historyLimit = 10;       // jumlah operasi yang bisa di-undo
    static String schematicsDir = "schematics";   // relatif ke folder game, atau path absolut

    private static Path file;

    private Config() {}

    static void load(Path configFile) {
        file = configFile;
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            } catch (IOException e) {
                SchemPrinterMod.LOG.warn("Gagal baca config: {}", e.toString());
            }
        }
        blocksPerTick = num(p, "blocks_per_tick", blocksPerTick);
        distance = num(p, "distance", distance);
        historyLimit = num(p, "history_limit", historyLimit);
        face = p.getProperty("face", face);
        schematicsDir = p.getProperty("schematics_dir", schematicsDir);
        boolean ok = false;
        for (String d : Layout.DIRS) if (d.equalsIgnoreCase(face)) ok = true;
        if (!ok) face = "south";
        save();
    }

    static void save() {
        if (file == null) return;
        Properties p = new Properties();
        p.setProperty("blocks_per_tick", Integer.toString(blocksPerTick));
        p.setProperty("distance", Integer.toString(distance));
        p.setProperty("history_limit", Integer.toString(historyLimit));
        p.setProperty("face", face);
        p.setProperty("schematics_dir", schematicsDir);
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                p.store(out, "Schem Printer. face = sisi schematic yang jadi muka patung (north/east/south/west)");
            }
        } catch (IOException e) {
            SchemPrinterMod.LOG.warn("Gagal simpan config: {}", e.toString());
        }
    }

    private static int num(Properties p, String key, int def) {
        try {
            return Integer.parseInt(p.getProperty(key, Integer.toString(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
