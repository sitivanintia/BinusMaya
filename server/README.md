# Server lisensi SESI MINI — Google Apps Script + Google Sheet

Key hanya berlaku setelah diverifikasi ke server ini; tidak ada secret di dalam APK. Key bisa dicabut (Status → `revoked`), diberi masa berlaku, dan dibatasi jumlah perangkat langsung dari spreadsheet.

## Setup (±5 menit)
1. Buat Google Sheet baru → **Extensions → Apps Script**.
2. Hapus isi `Code.gs`, tempel isi `server/Code.gs` dari repo ini, **Simpan**.
3. Di editor pilih fungsi `setupSheet` → **Run** (izinkan akses saat diminta). Sheet `Licenses` dengan header akan dibuat.
4. **Deploy → New deployment → ⚙ Web app** → *Execute as:* **Me** · *Who has access:* **Anyone** → Deploy. Salin **Web app URL** (`https://script.google.com/macros/s/…/exec`).
5. Masukkan URL itu ke `app/src/main/res/values/strings.xml` → `license_url`, lalu build APK. (Untuk uji cepat tanpa build: di layar lisensi APK, tekan lama judul **Aktivasi SESI MINI** → tempel URL.)

## Membuat key
Di editor Apps Script jalankan dari fungsi `generateKeys` atau buat fungsi sementara:
```js
function buat() { generateKeys(5, 1, 0, 'Batch September'); }   // 5 key, 1 perangkat, lifetime
function buat30() { generateKeys(1, 2, 30, 'Trial'); }            // 1 key, 2 perangkat, 30 hari
```
Key muncul di sheet (format `SESI-XXXX-XXXX-XXXX`) dan di **Execution log**. Bisa juga ketik key sendiri di kolom A (huruf/angka tanpa 0, 1, I, O).

## Kelola
- **Cabut**: ubah kolom *Status* menjadi `revoked` → aplikasi terkunci pada verifikasi berikutnya (maksimal 3 hari untuk yang offline).
- **Pindah HP**: hapus Device ID dari kolom *Perangkat*, atau tambah *Max Perangkat*.
- **Perpanjang**: isi/ubah kolom *Kedaluwarsa* (kosong = lifetime).
- Kolom *Terakhir Dilihat* terisi otomatis setiap aplikasi memverifikasi.

## Protokol
`POST {action: activate|verify|deactivate, key, deviceId, nonce, note}` → `{ok, status, name, expiresAt, graceUntil, devices, maxDevices, serverTime, deviceId, nonce}` atau `{ok:false, reason: not_found|revoked|expired|device_limit|device_mismatch|bad_request|server_setup}`.
Aplikasi hanya mempercayai respons HTTPS dari URL yang dikonfigurasi (Android 7+ tidak mempercayai CA buatan pengguna), menyimpan hasil dengan HMAC lokal, dan memverifikasi ulang setiap kali dibuka (min. tiap 6 jam). Tanpa internet, aktivasi tersimpan berlaku sampai `graceUntil` (3 hari), lalu wajib online lagi.

## APK SESI Admin (generate / cabut / monitoring dari HP)
1. Tempel `Code.gs` versi terbaru ke editor (menimpa yang lama), simpan.
2. Pilih fungsi **`setupAdmin`** → Run. Token admin (`ADM-XXXXXX-XXXXXX-XXXXXX`) tercetak di **Execution log**; token disimpan di Script Properties, bukan di kode.
3. **Deploy → Manage deployments → ✏ → Version: New version → Deploy** (URL tidak berubah).
4. Install `dist/SesiAdmin-x.y.z.apk` → masukkan token → Masuk.

Fitur: buat key (jumlah, max perangkat, masa berlaku, label), nonaktifkan/aktifkan, lepas perangkat, ubah nama/max/expiry, hapus, salin/kirim key via WA, monitoring (total, aktif, HP terdaftar, online 24 jam, mati) dengan filter & pencarian. Token bocor → jalankan `resetAdminToken()`.

## Update sistem (folder Google Drive, deteksi dari nama file)
1. Buat satu folder di Google Drive (tidak perlu di-share; server membacanya sebagai akunmu).
2. Di `Code.gs`, fungsi `setUpdatesFolder()`: ganti `TEMPEL_LINK_FOLDER_DI_SINI` dengan link folder → simpan → Run `setUpdatesFolder`. (Atau minta developer membake link ke konstanta `UPDATES_FOLDER`.) Deploy versi baru.
3. Rilis update = **drop file ke folder** dengan nama berversi. Server memilih versi tertinggi per komponen:
   - System MD: `Introvert-Dreams-SKILL-v6.md` (nama mengandung "skill"/"introvert", akhiran `.md`)
   - Auto prompt: `auto-prompt-v2.js`
   - Paksa 30 detik: `single-clip-enforcer-v2.js`
   - Pendeteksi video: `inject-v2.js`
   Versi bawaan APK: MD **v5**, lainnya **v1**. Angka boleh bertitik (`v2.1`); `v10` > `v2`.
4. Di APK: dashboard → **Update sistem → Cek & update**. File diambil lewat server (base64, maks 2 MB) dan langsung dipakai. Long-press baris untuk kembali ke bawaan APK.
