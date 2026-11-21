# GuessWho - Minigame "Guess Who Am I" untuk Paper (Java + Bedrock via Geyser)

## Build
Butuh JDK 25 dan koneksi internet (untuk download Paper API).

```
./gradlew build
```
Hasilnya: `build/libs/GuessWho-1.0.0.jar` -> taruh di folder `plugins/` server Paper.

Kalau server Paper kamu bukan 26.3, ubah versi di `build.gradle.kts`
(`paper-api:26.3-R0.1-SNAPSHOT`) dan `api-version` di `plugin.yml`.

## Setup arena (sekali saja, oleh host/OP)
1. Bangun meja bundar. Kursi/posisi pemain melingkar di sekelilingnya.
2. Berdiri di **tengah meja** (posisi kamera) -> `/gw setcamera`
3. Berdiri di posisi tiap pemain -> `/gw setseat 1`, `/gw setseat 2`, ... (sampai jumlah pemain)
4. Cek dengan `/gw arena`

Penting: tiap kursi perlu ruang kosong **2 blok di atas kepala** dan 1 blok ke arah meja,
karena di situ gambar dipasang (item frame di samping blok barrier yang tidak terlihat).
Kalau ruang itu terhalang, gambar tidak muncul.

## Menambah tema dan gambar
Taruh gambar (PNG/JPG) di `plugins/GuessWho/themes/<nama-tema>/`.
**Nama file = jawaban yang benar** (`ender_man.png` -> "ender man"). Lalu `/gw reload`.
Satu tema butuh gambar minimal sebanyak pemain. Gambar dikecilkan jadi 128x128 (map).

## Cara main
- Pemain: `/gw join`. Host bisa pakai `/gw joinall`.
- Host: `/gw start <tema|random>`. Gambar diacak otomatis ke tiap pemain.
- Semua pemain dikunci di tengah meja dan melihat lewat kamera yang berputar ke pemain giliran.
- Pemain giliran muncul di kursinya (first person, tangan tidak terlihat). Bertanya dengan **mengetik langsung di chat**,
  menebak dengan `/gw guess <jawaban>`, skip giliran dengan `/gw next`.
- Tiap pemain punya 3 nyawa (`lives` di config), tampil di sidebar, bossbar, dan di atas kepala. Tebakan salah = -1 nyawa
  (efek suara/partikel), nyawa habis = tereliminasi (efek petir).
- Host: `/gw next` (lompat giliran siapa pun), `/gw reveal [pemain]`, `/gw stop`.
- Gambar tiap pemain disembunyikan dari pemiliknya sendiri, tapi terlihat oleh pemain lain.

## Pengaturan (`config.yml`)
`camera-speed`, `turn-delay-ticks`, `turn-seconds` (0 = tanpa batas), `image-height`,
`look-height`, `wrong-guess-ends-turn`, `lives`.

## Catatan
- Kamera memakai teleport + putar arah pandang tiap tick, sehingga jalan sama di Java dan
  Bedrock (Geyser/Floodgate). Gerakannya 20 fps, jadi tidak semulus kamera packet.
- Belum diuji di server sungguhan. Kalau ada bug, kirim pesan error/log-nya.
