package dev.schemprinter;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.LevelResource;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Semua logika: print / paste / pause / undo. Satu proses aktif sekali waktu. */
final class Manager {
    /** 2 = kirim ke client, 16 = tanpa neighbor shape update, 32 = tanpa drop item. */
    static final int FLAGS = 2 | 16 | 32;
    /** Maks waktu kerja per tick server (50 ms = 1 tick), biar game gak freeze walau patung gede. */
    static final long TICK_BUDGET_NANOS = 40_000_000L;

    static MinecraftServer server;
    static Task current;
    static final ArrayDeque<UndoOp> history = new ArrayDeque<>();
    private static Path historyDir;
    private static long lastId;

    private Manager() {}

    // ============================================================
    // MODEL
    // ============================================================
    /** Catatan blok ASLI sebelum ditimpa (urutan penempatan), dipakai buat undo. */
    static final class UndoOp {
        final long id;
        final String dim;
        final String name;
        ServerLevel level;                       // null kalau baru di-load dari disk
        final List<BlockState> palette = new ArrayList<>();
        final Map<BlockState, Integer> palIdx = new HashMap<>();
        long[] pos = new long[1024];
        int[] idx = new int[1024];
        int n;

        UndoOp(long id, String dim, String name, ServerLevel level) {
            this.id = id;
            this.dim = dim;
            this.name = name;
            this.level = level;
        }

        void record(long p, BlockState old) {
            Integer i = palIdx.get(old);
            if (i == null) {
                i = palette.size();
                palette.add(old);
                palIdx.put(old, i);
            }
            if (n == pos.length) {
                pos = Arrays.copyOf(pos, n * 2);
                idx = Arrays.copyOf(idx, n * 2);
            }
            pos[n] = p;
            idx[n] = i;
            n++;
        }
    }

    abstract static class Task {
        boolean paused;
        int lastQuarter;

        /** Return true kalau udah selesai. */
        abstract boolean step(long deadlineNanos);

        abstract String label();

        abstract int percent();
    }

    /** Urutan cetak. Kiri/kanan = dilihat dari posisi player waktu mulai print (ngadep patung). */
    enum Mode {
        BOTTOM("bawah -> atas"), LEFT("kiri -> kanan"), RIGHT("kanan -> kiri"), MIDDLE("tengah -> kiri & kanan");

        final String desc;

        Mode(String desc) {
            this.desc = desc;
        }
    }

    static final class PrintTask extends Task {
        final ServerLevel level;
        final UndoOp op;
        final char[] ids;
        final BlockState[] states;
        final int w, h, l, ox, oy, oz;
        final boolean air, instant;
        final int perTick;
        final long total;
        final Mode mode;
        /** Mode samping: sumbu kiri-kanan = X? n = jumlah irisan, m = kedalaman irisan, S = blok per irisan. */
        final boolean alongX, leftIsMin;
        final int n, m, slice;
        int cursor;
        private int cx, cy, cz;

        PrintTask(ServerLevel level, UndoOp op, char[] ids, BlockState[] states, int w, int h, int l,
                  int ox, int oy, int oz, boolean air, boolean instant, int perTick, Mode mode, int playerDir) {
            this.level = level;
            this.op = op;
            this.ids = ids;
            this.states = states;
            this.w = w;
            this.h = h;
            this.l = l;
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.air = air;
            this.instant = instant;
            this.perTick = perTick;
            this.total = (long) w * h * l;
            this.mode = instant ? Mode.BOTTOM : mode;
            // ngadep utara/selatan -> kiri-kanan itu sumbu X; timur/barat -> sumbu Z.
            // Kanan player = arah (p+1): utara->timur(+X), timur->selatan(+Z), selatan->barat(-X), barat->utara(-Z).
            this.alongX = playerDir == 0 || playerDir == 2;
            this.leftIsMin = playerDir == 0 || playerDir == 1;
            this.n = alongX ? w : l;
            this.m = alongX ? l : w;
            this.slice = h * m;
        }

        /** Cursor ke koordinat lokal (cx, cy, cz) sesuai mode. */
        private void locate(int c) {
            if (mode == Mode.BOTTOM) {
                // layer bawah -> atas, tiap baris zig-zag (serpentine)
                int layer = w * l;
                int y = c / layer, rem = c - y * layer, z = rem / w, xi = rem - z * w;
                cx = (z & 1) == 1 ? w - 1 - xi : xi;
                cy = y;
                cz = z;
                return;
            }
            // mode samping: per irisan vertikal, tiap irisan bawah -> atas, baris zig-zag
            int k, r;                                   // k = irisan ke-k dari kiri, r = index di dalam irisan
            if (mode == Mode.MIDDLE) {
                int center = (n & 1) == 1 ? slice : 0;  // lebar ganjil: irisan tengah dicetak duluan sendirian
                if (c < center) {
                    k = n / 2;
                    r = c;
                } else {
                    // sisanya sepasang kiri+kanan, bloknya diselang-seling biar jalan barengan
                    int j = c - center, pair = j / (2 * slice), pr = j - pair * 2 * slice;
                    r = pr >> 1;
                    int leftK = (n - 1) / 2 - (center > 0 ? 1 : 0) - pair;
                    k = (pr & 1) == 0 ? leftK : n - 1 - leftK;
                }
            } else {
                int s = c / slice;
                r = c - s * slice;
                k = mode == Mode.LEFT ? s : n - 1 - s;
            }
            int y = r / m, d = r - y * m;
            if ((y & 1) == 1) d = m - 1 - d;
            int a = leftIsMin ? k : n - 1 - k;
            cy = y;
            if (alongX) {
                cx = a;
                cz = d;
            } else {
                cx = d;
                cz = a;
            }
        }

        @Override
        boolean step(long deadline) {
            int placed = 0, scanned = 0;
            while (cursor < total) {
                if ((++scanned & 255) == 0 && System.nanoTime() > deadline) break;
                if (!instant && placed >= perTick) break;
                locate(cursor++);
                int x = cx, y = cy, z = cz;
                BlockState st = states[ids[(y * l + z) * w + x]];
                if (!air && st.isAir()) continue;
                int wy = oy + y;
                if (level.isOutsideBuildHeight(wy)) continue;
                BlockPos p = new BlockPos(ox + x, wy, oz + z);
                BlockState old = level.getBlockState(p);
                if (old == st) continue;
                op.record(p.asLong(), old);
                level.setBlock(p, st, FLAGS);
                placed++;
            }
            return cursor >= total;
        }

        @Override
        String label() {
            return instant ? "Paste " + op.name : "Print " + op.name + " (" + mode.desc + ")";
        }

        @Override
        int percent() {
            return (int) (100L * cursor / total);
        }
    }

    static final class UndoTask extends Task {
        final ServerLevel level;
        final UndoOp op;
        final int total;
        int cursor;

        UndoTask(ServerLevel level, UndoOp op) {
            this.level = level;
            this.op = op;
            this.total = op.n;
            this.cursor = op.n;
        }

        @Override
        boolean step(long deadline) {
            int scanned = 0;
            while (cursor > 0) {
                if ((++scanned & 255) == 0 && System.nanoTime() > deadline) break;
                cursor--;                                    // mundur dari yang terakhir ditimpa
                level.setBlock(BlockPos.of(op.pos[cursor]), op.palette.get(op.idx[cursor]), FLAGS);
            }
            return cursor == 0;
        }

        @Override
        String label() {
            return "Undo " + op.name;
        }

        @Override
        int percent() {
            return total == 0 ? 100 : 100 - (int) (100L * cursor / total);
        }
    }

    // ============================================================
    // EVENT
    // ============================================================
    static void tick(MinecraftServer s) {
        Task t = current;
        if (t == null || t.paused) return;
        boolean done;
        try {
            done = t.step(System.nanoTime() + TICK_BUDGET_NANOS);
        } catch (Throwable e) {
            SchemPrinterMod.LOG.error("Schem Printer error", e);
            broadcast("[Schem] Error: " + e + " (proses dihentikan, /schem undo buat balikin yang udah kecetak)");
            finish(t, false);
            return;
        }
        if (done) {
            finish(t, true);
            return;
        }
        int q = t.percent() / 25;
        if (q > t.lastQuarter) {
            t.lastQuarter = q;
            broadcast("[Schem] " + t.label() + ": " + t.percent() + "%");
        }
    }

    private static void finish(Task t, boolean ok) {
        current = null;
        if (t instanceof PrintTask pt) {
            persist(pt.op);
            if (ok) broadcast("[Schem] Selesai: " + pt.op.name + ". /schem undo buat balikin kondisi awal.");
        } else if (t instanceof UndoTask ut && ok) {
            history.remove(ut.op);
            deleteFile(ut.op);
            broadcast("[Schem] Undo selesai (" + ut.op.n + " blok dibalikin).");
        }
    }

    static void onStart(MinecraftServer s) {
        server = s;
        current = null;
        history.clear();
        historyDir = s.getWorldPath(LevelResource.ROOT).resolve("schemprinter_history");
        loadHistory();
    }

    static void onStop(MinecraftServer s) {
        if (current instanceof PrintTask pt) persist(pt.op);
        current = null;
        history.clear();
        server = null;
    }

    // ============================================================
    // COMMAND
    // ============================================================
    static int start(CommandSourceStack src, String name, boolean instant, boolean air, Mode mode) throws CommandSyntaxException {
        if (current != null) {
            fail(src, "Masih ada proses jalan (" + current.label() + "). /schem stop atau /schem undo dulu.");
            return 0;
        }
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = src.getLevel();

        Path file = resolve(name);
        if (file == null) {
            fail(src, "Schematic '" + name + "' gak ketemu di " + dir() + ". Cek /schem list");
            return 0;
        }

        Schem s;
        try {
            s = Schem.load(file);
        } catch (Exception e) {
            SchemPrinterMod.LOG.error("Gagal baca {}", file, e);
            fail(src, "Gagal baca schematic: " + e.getMessage());
            return 0;
        }

        int p = Layout.playerDir(player.getYRot());
        int k = Layout.rotationSteps(p, Layout.faceIndex(Config.face));
        for (int i = 0; i < k; i++) s = s.rotateCw();

        // palette -> BlockState, lalu diputar k kali (stairs, pintu, dll ikut menghadap benar)
        BlockState[] states = new BlockState[s.palette.length];
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < states.length; i++) {
            BlockState st = parseState(s.palette[i]);
            if (st == null) {
                missing.add(s.palette[i]);
                st = Blocks.AIR.defaultBlockState();
            }
            for (int r = 0; r < k; r++) st = st.rotate(Rotation.CLOCKWISE_90);
            states[i] = st;
        }

        long solid = 0;
        for (char id : s.ids) if (!states[id].isAir()) solid++;
        if (solid == 0 && !air) {
            fail(src, "Schematic kosong (semua air).");
            return 0;
        }

        int[] off = Layout.originOffset(p, s.w, s.l, Config.distance);
        BlockPos anchor = player.blockPosition();
        int ox = anchor.getX() + off[0], oy = anchor.getY(), oz = anchor.getZ() + off[1];

        UndoOp op = new UndoOp(nextId(), level.dimension().toString(), baseName(file), level);
        history.addLast(op);
        while (history.size() > Math.max(1, Config.historyLimit)) deleteFile(history.pollFirst());

        PrintTask task = new PrintTask(level, op, s.ids, states, s.w, s.h, s.l, ox, oy, oz, air, instant,
                Config.blocksPerTick, mode, p);
        current = task;

        String info = s.w + "x" + s.h + "x" + s.l + ", " + solid + " blok";
        if (instant) {
            say(src, "[Schem] Paste " + op.name + " (" + info + ").");
        } else {
            int secs = (int) (solid / Math.max(1, Config.blocksPerTick) / 20);
            say(src, "[Schem] Print " + op.name + " (" + info + ", " + mode.desc + ", ~" + secs + " detik). /schem pause, /schem stop, /schem undo");
        }
        if (!missing.isEmpty())
            say(src, "[Schem] " + missing.size() + " jenis blok gak dikenal (mod belum dipasang?), diganti air. Contoh: " + missing.get(0));
        return 1;
    }

    static int pause(CommandSourceStack src, boolean pause) {
        if (current == null) {
            fail(src, "Gak ada proses yang jalan.");
            return 0;
        }
        current.paused = pause;
        say(src, "[Schem] " + current.label() + (pause ? ": PAUSE (/schem resume buat lanjut)." : ": lanjut."));
        return 1;
    }

    static int stop(CommandSourceStack src) {
        if (current == null) {
            fail(src, "Gak ada proses yang jalan.");
            return 0;
        }
        Task t = current;
        current = null;
        if (t instanceof PrintTask pt) persist(pt.op);
        say(src, "[Schem] Dihentikan. Yang udah kecetak dibiarin (/schem undo buat balikin).");
        return 1;
    }

    static int undo(CommandSourceStack src) {
        if (current instanceof UndoTask) {
            fail(src, "Undo lagi jalan.");
            return 0;
        }
        if (current != null) {                       // print lagi jalan: batalin, lalu balikin yang udah kecetak
            current = null;
            say(src, "[Schem] Print dibatalin, balikin yang udah kecetak...");
        }
        UndoOp op = history.peekLast();
        if (op == null) {
            fail(src, "Gak ada yang bisa di-undo.");
            return 0;
        }
        if (op.n == 0) {
            history.pollLast();
            deleteFile(op);
            say(src, "[Schem] Operasi terakhir (" + op.name + ") gak ngubah apa-apa, dibuang dari history.");
            return 1;
        }
        ServerLevel lv = op.level != null ? op.level : findLevel(op.dim);
        if (lv == null) {
            fail(src, "Dimensi buat undo '" + op.name + "' gak ketemu.");
            return 0;
        }
        current = new UndoTask(lv, op);
        say(src, "[Schem] Undo " + op.name + " (" + op.n + " blok)...");
        return 1;
    }

    static int status(CommandSourceStack src) {
        say(src, current == null ? "[Schem] Idle." :
                "[Schem] " + current.label() + " " + current.percent() + "%" + (current.paused ? " (PAUSE)" : ""));
        say(src, "[Schem] History undo: " + history.size() + " operasi. Speed: " + Config.blocksPerTick
                + " blok/tick, jarak: " + Config.distance + ", muka patung: " + Config.face);
        return 1;
    }

    static int list(CommandSourceStack src) {
        List<String> names = listNames();
        if (names.isEmpty()) {
            say(src, "[Schem] Folder kosong: " + dir() + " (taruh file .schem di situ)");
        } else {
            say(src, "[Schem] " + names.size() + " schematic di " + dir() + ": " + String.join(", ", names));
        }
        return 1;
    }

    static int setSpeed(CommandSourceStack src, int v) {
        Config.blocksPerTick = v;
        Config.save();
        say(src, "[Schem] Speed print: " + v + " blok/tick.");
        return 1;
    }

    static int setDistance(CommandSourceStack src, int v) {
        Config.distance = v;
        Config.save();
        say(src, "[Schem] Jarak patung dari lo: " + v + " blok.");
        return 1;
    }

    static int setFace(CommandSourceStack src, String f) {
        Config.face = f;
        Config.save();
        say(src, "[Schem] Muka patung ada di sisi " + f + " (di file schematic).");
        return 1;
    }

    // ============================================================
    // FILE SCHEMATIC
    // ============================================================
    static Path dir() {
        Path d = Path.of(Config.schematicsDir);
        if (!d.isAbsolute()) d = FabricLoader.getInstance().getGameDir().resolve(d);
        return d.normalize();
    }

    static List<String> listNames() {
        Path d = dir();
        try {
            Files.createDirectories(d);
            try (Stream<Path> st = Files.list(d)) {
                return st.map(x -> x.getFileName().toString())
                        .filter(x -> x.toLowerCase().endsWith(".schem"))
                        .map(x -> x.substring(0, x.length() - 6))
                        .sorted()
                        .toList();
            }
        } catch (IOException e) {
            return List.of();
        }
    }

    private static Path resolve(String name) {
        try {
            Path d = dir();
            Files.createDirectories(d);
            Path a = d.resolve(name).normalize();
            if (a.startsWith(d) && Files.isRegularFile(a)) return a;
            Path b = d.resolve(name + ".schem").normalize();
            if (b.startsWith(d) && Files.isRegularFile(b)) return b;
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String baseName(Path f) {
        String n = f.getFileName().toString();
        return n.toLowerCase().endsWith(".schem") ? n.substring(0, n.length() - 6) : n;
    }

    // ============================================================
    // BLOCKSTATE <-> STRING
    // ============================================================
    static BlockState parseState(String s) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, s, false).blockState();
        } catch (CommandSyntaxException | RuntimeException e) {
            return null;
        }
    }

    static String stateToString(BlockState s) {
        StringBuilder sb = new StringBuilder(BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString());
        Collection<Property<?>> props = s.getProperties();
        if (!props.isEmpty()) {
            sb.append('[');
            boolean first = true;
            for (Property<?> p : props) {
                if (!first) sb.append(',');
                first = false;
                sb.append(p.getName()).append('=').append(valueName(p, s));
            }
            sb.append(']');
        }
        return sb.toString();
    }

    private static <T extends Comparable<T>> String valueName(Property<T> p, BlockState s) {
        return p.getName(s.getValue(p));
    }

    // ============================================================
    // HISTORY DI DISK (biar undo tetap bisa setelah game ditutup)
    // ============================================================
    private static Path fileOf(UndoOp op) {
        return historyDir.resolve("op_" + op.id + ".bin");
    }

    private static void deleteFile(UndoOp op) {
        if (op == null || historyDir == null) return;
        try {
            Files.deleteIfExists(fileOf(op));
        } catch (IOException ignored) {
        }
    }

    static void persist(UndoOp op) {
        if (historyDir == null) return;
        if (op.n == 0) {                       // gak ngubah apa-apa
            history.remove(op);
            deleteFile(op);
            return;
        }
        try {
            Files.createDirectories(historyDir);
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                    new GZIPOutputStream(Files.newOutputStream(fileOf(op)))))) {
                out.writeUTF(op.dim);
                out.writeUTF(op.name);
                out.writeInt(op.palette.size());
                for (BlockState st : op.palette) out.writeUTF(stateToString(st));
                out.writeInt(op.n);
                for (int i = 0; i < op.n; i++) {
                    out.writeLong(op.pos[i]);
                    out.writeInt(op.idx[i]);
                }
            }
        } catch (IOException e) {
            SchemPrinterMod.LOG.error("Gagal simpan history undo", e);
        }
    }

    private static void loadHistory() {
        if (!Files.isDirectory(historyDir)) return;
        List<Path> files;
        try (Stream<Path> st = Files.list(historyDir)) {
            files = st.filter(f -> f.getFileName().toString().matches("op_\\d+\\.bin")).sorted().toList();
        } catch (IOException e) {
            return;
        }
        // urutkan berdasarkan id angka (bukan alfabet)
        List<Path> sorted = new ArrayList<>(files);
        sorted.sort((a, b) -> Long.compare(idOf(a), idOf(b)));
        for (Path f : sorted) {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(f))))) {
                String dim = in.readUTF(), name = in.readUTF();
                UndoOp op = new UndoOp(idOf(f), dim, name, null);
                int pc = in.readInt();
                for (int i = 0; i < pc; i++) {
                    BlockState st = parseState(in.readUTF());
                    op.palette.add(st != null ? st : Blocks.AIR.defaultBlockState());
                }
                int n = in.readInt();
                op.pos = new long[Math.max(n, 1)];
                op.idx = new int[Math.max(n, 1)];
                for (int i = 0; i < n; i++) {
                    op.pos[i] = in.readLong();
                    op.idx[i] = in.readInt();
                }
                op.n = n;
                history.addLast(op);
                lastId = Math.max(lastId, op.id);
            } catch (Exception e) {
                SchemPrinterMod.LOG.warn("History rusak, dilewati: {} ({})", f.getFileName(), e.toString());
            }
        }
        while (history.size() > Math.max(1, Config.historyLimit)) deleteFile(history.pollFirst());
        if (!history.isEmpty())
            SchemPrinterMod.LOG.info("Schem Printer: {} operasi undo dimuat dari disk.", history.size());
    }

    private static long idOf(Path f) {
        String n = f.getFileName().toString();
        return Long.parseLong(n.substring(3, n.length() - 4));
    }

    private static long nextId() {
        lastId = Math.max(lastId + 1, System.currentTimeMillis());
        return lastId;
    }

    private static ServerLevel findLevel(String dim) {
        if (server == null) return null;
        for (ServerLevel lv : server.getAllLevels()) if (lv.dimension().toString().equals(dim)) return lv;
        return null;
    }

    // ============================================================
    // PESAN
    // ============================================================
    private static void say(CommandSourceStack src, String m) {
        src.sendSuccess(() -> Component.literal(m), false);
    }

    private static void fail(CommandSourceStack src, String m) {
        src.sendFailure(Component.literal(m));
    }

    private static void broadcast(String m) {
        if (server != null) server.getPlayerList().broadcastSystemMessage(Component.literal(m), false);
    }
}
