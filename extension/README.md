# SESI MINI — Extension Chrome / Kiwi (Manifest V3)

Fitur sama dengan APK: pendeteksi video HD tanpa watermark (`inject.js`), auto prompt Seedance 2.5 / 30 detik + paksa 1 video (`auto-prompt.js`, `single-clip-enforcer.js`), lampiran System MD, sesi akun (cookies + localStorage), lisensi online (server Apps Script yang sama, Device ID sendiri per browser), dan Update sistem (MD diperbarui OTA dari folder Drive; skrip JS memberi tahu bila ada versi baru dan perlu extension build baru — MV3 tidak mengizinkan skrip content dinamis).

## Pasang
- **Kiwi (Android):** menu ⋮ → **Extensions** → **+ (from .zip)** → pilih `SesiMini-extension-x.y.z.zip`. Atau ekstrak dan pakai **Load unpacked**.
- **Chrome desktop:** `chrome://extensions` → Developer mode → **Load unpacked** → folder hasil ekstrak zip.
- Buka https://www.dola.com → dock kiri (💉 MD · ⚡ Auto prompt · 🖼 Image referensi) dan tombol SESI di kanan membuka dashboard overlay. Ikon extension di toolbar membuka dashboard yang sama sebagai popup.
- Aktivasi: masukkan license key (Device ID extension berbeda dari APK; 1 key = jumlah perangkat sesuai *Max Perangkat* di sheet).

## Build
`tools/build-extension.sh` → menyalin `extension/` + aset bersama dari `app/src/main/assets`, membuat `ext-bridge.js` (template + `attach-md.js`), lalu zip ke `dist/SesiMini-extension-<versi>.zip`. Naikkan `version` di `extension/manifest.json` saat rilis.

## Struktur
- `manifest.json` — MV3; content scripts MAIN world (`auto-prompt.js`, `inject.js`, `single-clip-enforcer.js`, `ext-bridge.js`) di `document_start`, isolated (`content.js`).
- `ext-bridge.template.js` — jembatan MAIN world: emulasi `IDBridge` Android, perintah `autoPrompt/imageNote/scan/attach` via `postMessage`.
- `content.js` — dock, RPC, simpan video ke `chrome.storage.local`, relay ke dashboard.
- `background.js` — `chrome.downloads` (folder `Download/SesiMini/`), status Saving/Saved, verifikasi lisensi tiap 6 jam.
- `dashboard.html/js/css` — popup & overlay: gate lisensi, video list (thumbnail, BARU, pemutar), kontrol, akun, update sistem.
