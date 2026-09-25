# Introvert Dreams — Android (WebView Dola + HD Downloader)

APK yang memuat dola.com di WebView dengan dashboard unduhan. Request Dola tidak ditulis ulang dan isi pesan tidak disisipi directive.

## Lampiran MD (v1.3.0)
- Dola membuat input file **on-demand** saat menu "Unggah File atau Gambar" diketuk. Saat pill ID aktif, ketukan berikutnya pada menu itu dicegat dan file MD langsung dilampirkan (picker sistem tidak dibuka), lalu pill kembali nonaktif (one-shot). Input khusus gambar (`accept="image/*"`) tidak pernah dicegat.
- Tombol dashboard: ikon kecil di kanan tengah (tidak menutupi tombol kirim).
- Upload file/gambar di Dola kini berfungsi (WebView `onShowFileChooser` + izin kamera/mikrofon).
- Toggle adalah pill kecil "ID" di kiri tengah: oranye = nonaktif, hijau "ID ●" = file MD sudah diserahkan ke composer.
- Tombol **Aktifkan!** di kiri tengah melampirkan `Introvert-Dreams-SKILL-v5.md` bawaan ke input file composer. File sama persis dengan lampiran pengguna.
- Upload ditangani aplikasi web Dola melalui event pemilihan file; tidak mengirim pesan otomatis. Status Aktif berarti file diserahkan ke composer, bukan bukti upload server selesai. Periksa indikator lampiran Dola sebelum mengirim.
- Jika input file dokumen belum tersedia atau ambigu, buka menu lampiran dokumen Dola lalu coba lagi. Input khusus gambar tidak digunakan.
- Ketuk Aktif untuk menonaktifkan kontrol. Ini tidak menghapus lampiran yang sudah diunggah; hapus dari composer bila diperlukan. Tidak ada upload otomatis setelah navigasi atau pada pesan berikutnya.
- Belum diverifikasi terhadap akun Dola asli atau perangkat Android. Tes browser memakai composer tiruan.

- `assets/inject.js` — pengamat *read-only* respons API Dola: mengumpulkan URL video kualitas sumber (`fallback_api` / `video_list`, piksel & bitrate tertinggi). Tidak mengubah request.
- Tombol bulat kanan bawah → dashboard: status, Found/Saved, **Scan chat** (manual), daftar video + **Download** per item ke `Download/IntrovertDreams/`.

## Deteksi & Download (v1.5.0 — sistem SESI MAX MODE)
- `assets/inject.js` memakai pipeline extractor SESI MAX MODE: `fallback_api` disadap dari respons API Dola (fetch/XHR, termasuk JSON bersarang dalam string), lalu diambil ulang dengan `channel=no&codec_type=8&logo_type=unwatermarked`, entri kualitas tertinggi dipilih, dan token `main_url` didekode (URL langsung / base64 / `qAAB` AES-CBC dengan `key_seed`). Versi lama menganggap `fallback_api` sebagai URL video sehingga tidak ada video valid yang terdeteksi/terunduh.
- Unduhan lewat Android DownloadManager persis seperti SESI: Android 10+ ke folder `Download/`, Android 8–9 ke folder Download khusus aplikasi (tanpa izin penyimpanan). Status **Saving/Saved/Gagal** di dashboard mengikuti status DownloadManager yang dilacak di SharedPreferences (`downloads`), bukan ditandai saat enqueue.
- Navigasi mengikuti SESI: semua halaman http(s) (login Google/Apple/Facebook, redirect, CDN) tetap di WebView yang sama sehingga login kembali ke Dola; hanya WhatsApp/intent/mailto/tel yang keluar aplikasi.
- Daftar video: thumbnail di-decode native (`MediaMetadataRetriever` dengan cookie/UA Dola, atau poster dari API), nomor urut `#n`, terbaru di atas, lencana **BARU** untuk video yang belum terlihat saat dashboard terakhir dibuka, durasi, dan ketuk thumbnail → pop-up player native dengan tombol Download.
- **Auto prompt** & **Image referensi** (v1.7.0, dock kiri sejajar tombol inject MD): `assets/auto-prompt.js` dari SESI MAX MODE dijalankan di document-start; toggle petir membungkus setiap pengiriman chat dengan directive Seedance 2.5 / 30 detik single take (`__sesiAutoPrompt.activate()`), toggle gambar menambah catatan image referensi (`setImageNote`). Status disimpan di SharedPreferences dan dipulihkan otomatis tiap dokumen Dola. Ikon dashboard memakai brand icon SESI.
- **Paksa 1 video × 30 detik** (v1.8.0): `single-clip-enforcer.js` SESI MAX MODE dimuat setelah collector; diaktifkan bersama toggle Auto prompt (payload rewrite duration→30/count→1, forceModel25, auto-answer dialog split). Nonaktif secara default sampai toggle dinyalakan.
- Upload gambar: `onShowFileChooser` menghormati `accept` — input `image/*` membuka galeri/photo picker, input dokumen membuka file picker.
- **Akun** (tombol di dashboard): simpan sesi login Dola per akun (cookies via `CookieManager` + snapshot `localStorage`), ganti akun tanpa login ulang, “Akun baru” untuk logout bersih. Data tersimpan lokal.

## Lisensi online (v1.9.0)
- Gate lisensi memakai server Google Apps Script + Google Sheet di `server/` (lihat `server/README.md`). Tidak ada secret di APK; key dicek online, terikat Device ID, bisa dicabut/diberi masa berlaku/dibatasi perangkat dari spreadsheet.
- Aktivasi tersimpan lokal dengan HMAC (ANDROID_ID + package + Device ID) dan berlaku sampai `graceUntil` (3 hari) tanpa internet; aplikasi memverifikasi ulang tiap dibuka (min. 6 jam). Dola tidak dimuat, download/inject/toggle diblokir, sampai lisensi valid.
- URL server diisi di `res/values/strings.xml` → `license_url`; untuk uji tanpa build, tekan lama judul layar aktivasi lalu tempel URL.

## Update sistem OTA (v1.10.1)
Dashboard → **Update sistem**: server membaca satu folder Google Drive (`action=updates`) dan memilih file berversi tertinggi per komponen dari nama file (`Introvert-Dreams-SKILL-v6.md`, `auto-prompt-v2.js`, `single-clip-enforcer-v2.js`, `inject-v2.js`); isi file dikirim server (`action=update_file`) sehingga folder boleh privat. Salinan OTA di `files/updates/` dipakai oleh `readAsset()` dan lampiran MD. Lihat `server/README.md`.

## Edisi (v1.12.0)
- **user** (`SesiMini-x.y.z.apk`, id `com.introvertdreams.app`): untuk pengguna — gate lisensi online, tanpa agent, tanpa diagnostik.
- **dev** (`SesiMini-Dev-x.y.z.apk`, id `com.introvertdreams.app.dev`, nama "SESI MINI Dev"): untuk developer — **tanpa gate lisensi**, aplikasi pengguna utuh + tombol **🤖 AI Agent** di dashboard (login token admin), diagnostik selalu dikirim dari HP ini. Bisa terpasang berdampingan dengan edisi user.

## Diagnostik & AI Agent (v1.11.0)
Hanya dalam **mode developer** (tekan lama judul dashboard; default nonaktif) APK mengirim laporan anonim (`IDBridge.onReport` dari `inject.js`, download gagal, auto prompt not ready) ke `action=report` → sheet `Reports`. SESI Admin 1.1.0 punya **🤖 Agent** (provider OpenAI-compatible dengan model picker dari `/models`, tools read_asset/write_draft/publish, diff, diagnostik). Lihat `server/README.md`.

## SESI Admin (modul `admin/`)
APK terpisah (`com.introvertdreams.sesiadmin`) untuk mengelola lisensi dari HP lewat endpoint `admin_*` di server (token admin di Script Properties). Lihat `server/README.md`.

## Build
```
export ANDROID_HOME=/path/sdk
gradle assembleRelease
```

Tes attachment browser (Playwright + Chromium diperlukan): `node --test tests/attach-md.cjs`.
