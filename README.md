# Game Booster (Root + Non-root via Shizuku) + Frame Gen + Panel Floating

## Build lewat HP (tanpa Android Studio)
1. Buat repo baru di GitHub, upload semua isi folder ini (termasuk folder .github).
2. Tab Actions -> Build APK -> Run workflow.
3. Download artifact GameBooster-debug -> install APK-nya.

## Build lewat Android Studio
File > Open > pilih folder ini > Build > Build APK(s).

## Pemakaian
- Root: izinkan akses su saat diminta. Non-root: install Shizuku, aktifkan lewat Wireless Debugging.
- Pilih game, ketuk "Aktifkan Panel Floating" (izinkan overlay), bubble muncul.
- Buka game, ketuk bubble -> Frame Gen. Di dialog capture pilih "Bagikan satu aplikasi" -> game.

## Cara kerja Frame Gen
Layar game di-capture (MediaProjection) -> GPU (OpenGL ES 3) menghitung motion vector per blok 8x8
-> frame tengah dibuat dan ditampilkan di overlay tiap vsync (2x/3x/4x). Ini overlay, bukan hook ke engine game:
FPS asli game tidak berubah, ada tambahan latency ~1 frame, dan konten DRM/secure tidak bisa di-capture.
