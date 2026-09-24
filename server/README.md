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
