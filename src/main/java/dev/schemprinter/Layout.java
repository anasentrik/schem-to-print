package dev.schemprinter;

/** Matematika posisi/arah. Index arah (searah jarum jam): 0 utara, 1 timur, 2 selatan, 3 barat. */
final class Layout {
    static final String[] DIRS = {"north", "east", "south", "west"};

    private Layout() {}

    static int faceIndex(String face) {
        for (int i = 0; i < 4; i++) if (DIRS[i].equalsIgnoreCase(face)) return i;
        return 2;
    }

    /** Yaw Minecraft (0 = selatan, 90 = barat, -90 = timur, +-180 = utara) -> index arah hadap player. */
    static int playerDir(float yaw) {
        int q = Math.floorMod(Math.round(yaw / 90f), 4);
        return (q + 2) % 4;
    }

    /** Berapa kali schematic diputar 90 derajat searah jarum jam supaya mukanya ngadep ke player. */
    static int rotationSteps(int playerDir, int faceIdx) {
        return Math.floorMod(playerDir + 2 - faceIdx, 4);
    }

    /** Pojok-min patung (setelah diputar, ukuran w x l) relatif ke posisi player. Return {dx, dz}. */
    static int[] originOffset(int playerDir, int w, int l, int d) {
        switch (playerDir) {
            case 2:  return new int[]{-(w / 2), d};                  // ngadep selatan (+Z)
            case 0:  return new int[]{-(w / 2), -(l - 1) - d};       // ngadep utara (-Z)
            case 1:  return new int[]{d, -(l / 2)};                  // ngadep timur (+X)
            default: return new int[]{-(w - 1) - d, -(l / 2)};       // ngadep barat (-X)
        }
    }
}
