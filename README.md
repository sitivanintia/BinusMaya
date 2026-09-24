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

## Download (v1.4.1)
- Android 9 ke bawah: aplikasi meminta izin penyimpanan (`WRITE_EXTERNAL_STORAGE`) sebelum mengunduh; tanpa izin ini DownloadManager selalu gagal.
- Hasil DownloadManager dipantau: sukses → toast lokasi file; gagal → toast alasan (HTTP 403/404, penyimpanan penuh, dll.) lalu otomatis dicoba ulang lewat downloader internal (HttpURLConnection → MediaStore `Download/IntrovertDreams`).
- Status **Saved** hanya ditandai setelah file benar-benar tersimpan.

## Build
```
export ANDROID_HOME=/path/sdk
gradle assembleRelease
```

Tes attachment browser (Playwright + Chromium diperlukan): `node --test tests/attach-md.cjs`.
