// Arms/disarms MD attachment. Dola creates its <input type=file> on demand when the user taps
// "Unggah File atau Gambar", so we intercept the next programmatic .click() on a file input and
// feed the bundled MD instead of opening the system picker. One-shot per activation.
(function (base64, arm) {
  if (!/(^|\.)dola\.com$/i.test(location.hostname)) return { ok: false, message: 'Buka dola.com terlebih dahulu.' };
  const filename = 'Introvert-Dreams-SKILL-v5.md';
  const S = (window.__idreamsArm = window.__idreamsArm || { armed: false, installed: false });
  S.armed = !!arm;
  if (!S.armed) return { ok: true, message: 'Nonaktif.' };
  S.file = () => new File([Uint8Array.from(atob(base64), c => c.charCodeAt(0))], filename, { type: 'text/markdown' });
  const feed = input => {
    try {
      const dt = new DataTransfer(); dt.items.add(S.file());
      input.files = dt.files;
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
      S.armed = false; try { window.IDBridge?.onAttached(); } catch (_) {}
      return true;
    } catch (_) { return false; }
  };
  if (!S.installed) {
    S.installed = true;
    const origClick = HTMLInputElement.prototype.click;
    HTMLInputElement.prototype.click = function () {
      if (S.armed && this.type === 'file' && !/^image\/\*$/.test(this.accept || '')) {
        // Defer so the site has finished wiring its change listener.
        setTimeout(() => feed(this), 0);
        return;
      }
      return origClick.call(this);
    };
    // Some builds use showPicker() instead of click().
    if (HTMLInputElement.prototype.showPicker) {
      const origPicker = HTMLInputElement.prototype.showPicker;
      HTMLInputElement.prototype.showPicker = function () {
        if (S.armed && this.type === 'file' && !/^image\/\*$/.test(this.accept || '')) { setTimeout(() => feed(this), 0); return; }
        return origPicker.call(this);
      };
    }
  }
  // If a suitable input already exists in the composer, use it immediately.
  const existing = [...document.querySelectorAll('input[type="file"]')].find(i => !i.disabled && !/^image\/\*$/.test(i.accept || ''));
  if (existing && feed(existing)) return { ok: true, message: 'File MD dilampirkan ke composer.' };
  return { ok: true, message: 'Aktif. Ketuk + → "Unggah File atau Gambar" — file MD akan terlampir otomatis.' };
})
