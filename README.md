# Schem Printer (Fabric, Minecraft 26.3)

Taruh file `.schem` di folder, ketik command, patung muncul di DEPAN lo (muka ngadep ke lo, ikut arah hadap lo).
Ada mode print 3D (animasi), paste instan, pause, dan **undo beneran** (balikin blok asli, bukan jadi air).

## Build
Butuh JDK 25.

1. Pastikan ada Gradle wrapper. Paling gampang: download template resmi dari
   https://fabricmc.net/develop/template/ (pilih Minecraft 26.3), lalu copy `gradlew`, `gradlew.bat`,
   dan folder `gradle/` dari template ke folder ini. (Atau: `gradle wrapper --gradle-version 9.6.0`)
2. `./gradlew build`
3. Hasilnya: `build/libs/schem-printer-1.0.0.jar` (BUKAN yang `-sources`).

Kalau Gradle protes soal versi plugin/Loom, samain `gradle.properties` dan `build.gradle` dengan template resmi 26.3.

## Pasang
- Fabric Loader 0.19.5+, Fabric API `0.161.0+26.3`, lalu taruh `schem-printer-1.0.0.jar` di folder `mods`.
- Taruh file `.schem` di `<folder game>/schematics/` (`.minecraft/schematics`).
  Mau pakai folder lain (misal folder WorldEdit)? Ubah `schematics_dir` di `config/schemprinter.properties`.

## Command (cheat/OP aktif)
| Command | Fungsi |
|---|---|
| `/schem list` | daftar schematic |
| `/schem print <nama> [air]` | print 3D, layer bawah -> atas |
| `/schem print <nama> left [air]` | print dari sisi kiri -> kanan (per irisan vertikal) |
| `/schem print <nama> right [air]` | print dari sisi kanan -> kiri |
| `/schem print <nama> middle [air]` | print dari tengah, nyebar ke kiri & kanan barengan |
| `/schem print <nama> bottom [air]` | sama kayak tanpa mode (bawah -> atas) |
| `/schem paste <nama> [air]` | paste (secepat yang aman buat server, dipecah per tick) |
| `/schem pause` / `/schem resume` | pause dan lanjut |
| `/schem stop` | berhenti, hasil yang udah jadi dibiarin |
| `/schem undo` | balikin kondisi asli (kalau lagi print: dibatalin lalu dibalikin) |
| `/schem status` | status |
| `/schem speed <n>` | blok per tick buat mode print (default 40) |
| `/schem distance <n>` | jarak lo ke patung (default 3) |
| `/schem face <north/east/south/west>` | sisi file schematic yang jadi MUKA patung (default south) |

Kiri/kanan = dilihat dari posisi lo waktu ngetik command (ngadep patung). Tiap irisan tetap dicetak bawah -> atas.

Tambahan `air` = blok air/udara di schematic juga ikut ditimpa (buat ngosongin area). Tanpa itu, air dilewati.

## Catatan
- Undo disimpan ke disk (`<world>/schemprinter_history`), jadi tetap bisa setelah game ditutup. Default 10 operasi terakhir (`history_limit`).
- Undo balikin BLOK (blockstate). Isi chest, teks sign, dan data block entity lain TIDAK disimpan/dibalikin.
- Entity (mob, item) gak disentuh. Block entity dari schematic (isi chest dsb.) juga gak ikut dipaste.
- Posisi: dasar patung sejajar kaki lo, tengah-tengah di depan lo.
