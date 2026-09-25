# Server lisensi SESI MINI — Google Apps Script + Google Sheet

Key hanya berlaku setelah diverifikasi ke server ini; tidak ada secret di dalam APK. Key bisa dicabut (Status → `revoked`), diberi masa berlaku, dan dibatasi jumlah perangkat langsung dari spreadsheet.

## Setup (±5 menit)
> **Versi 4+:** cukup tempel `Code.gs` utuh (±235 baris), pilih fungsi **`setup`** → Run (setujui izin Sheet+Drive). Log menampilkan token admin, status sheet, dan nama folder update — atau error "KODE TIDAK LENGKAP" bila tempelan terpotong. Lalu Deploy → Manage deployments → ✏ → New version. `action=version` mengembalikan `{version, complete, adminReady, folder}` untuk diagnosis.

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

## AI Agent developer (APK SESI Admin → 🤖 Agent)
Agent memakai provider **OpenAI-compatible** (OpenAI, OpenRouter, Groq, DeepSeek, Ollama publik, dll.). Konfigurasi disimpan di Script Properties (`AI_BASE_URL`, `AI_API_KEY`, `AI_MODEL`) — API key tidak pernah sampai ke pengguna.
1. Tempel `Code.gs` terbaru → Run `setup` → deploy versi baru. Saat pertama Run, setujui izin tambahan (**UrlFetch** untuk memanggil provider).
2. Di SESI Admin → **🤖 Agent → ⚙ Provider**: isi Base URL + API key → **Ambil daftar model** (memanggil `GET /models` provider) → pilih model → Simpan.
3. Tab **Chat**: minta agent menyesuaikan MD / skrip. Tools yang tersedia: `read_asset`, `write_draft` (file lengkap, JS di-syntax-check, versi otomatis naik), `list_drafts`, `syntax_check`, `get_reports` (diagnostik dari aplikasi pengguna), `list_licenses`.
4. Tab **Draft**: **Lihat perubahan** (diff baris) → **Terapkan** memindahkan file ke folder update sehingga pengguna mendapatkannya lewat “Cek & update”. Tidak ada publish otomatis.
5. Tab **Diagnostik**: laporan anonim dari APK/extension (`action=report`): komponen, event (`main_url_decode_failed`, `no_main_url_in_payload`, `scan_no_fallback_api`, `download failed`, `auto_prompt not_ready`), detail berupa struktur/ringkasan error tanpa isi chat. Agent membaca ini untuk melihat perubahan perilaku Dola.

Batas: Apps Script maks ±6 menit per permintaan (agent berhenti sopan di 4,5 menit — ketik “lanjutkan”); file besar (enforcer ±54 KB) butuh model dengan konteks ≥ 64k. Aset bawaan (bila belum ada di folder Drive) dibaca dari repo GitHub publik (`BUNDLED_RAW`).
