const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');

test('armed MD attachment feeds Dola\'s on-demand file input without opening a picker', async () => {
  const assets = path.join(__dirname, '../app/src/main/assets');
  const script = fs.readFileSync(path.join(assets, 'attach-md.js'), 'utf8');
  const skill = fs.readFileSync(path.join(assets, 'Introvert-Dreams-SKILL-v5.md'));
  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage();
    await page.route('https://www.dola.com/**', route => route.fulfill({
      contentType: 'text/html',
      body: '<form><textarea>Prompt pengguna</textarea><button>Kirim</button></form>',
    }));
    await page.goto('https://www.dola.com/chat');
    // Dola-like: input created on demand and clicked programmatically.
    await page.evaluate(() => {
      window.sent = false; window.got = [];
      document.querySelector('form').onsubmit = e => { e.preventDefault(); window.sent = true; };
      window.openUpload = () => { const i = document.createElement('input'); i.type = 'file'; i.accept = '.pdf,.txt,.md,image/*';
        i.addEventListener('change', () => window.got.push([...i.files].map(f => f.name))); document.body.appendChild(i); i.click(); };
    });
    const arm = (on) => page.evaluate(`${script}(${JSON.stringify(skill.toString('base64'))}, ${on})`);
    const r1 = await arm(true); assert.equal(r1.ok, true);
    await page.evaluate(() => window.openUpload()); await page.waitForTimeout(50);
    const s1 = await page.evaluate(() => ({ got: window.got, sent: window.sent, prompt: document.querySelector('textarea').value }));
    assert.deepEqual(s1.got, [['Introvert-Dreams-SKILL-v5.md']]); assert.equal(s1.sent, false); assert.equal(s1.prompt, 'Prompt pengguna');
    const bytes = await page.evaluate(async () => [...new Uint8Array(await document.querySelector('input[type=file]').files[0].arrayBuffer())]);
    assert.deepEqual(Buffer.from(bytes), skill);
    // one-shot: second upload tap is not intercepted (would open picker; here just no change event with our file)
    await page.evaluate(() => window.openUpload()); await page.waitForTimeout(50);
    assert.equal((await page.evaluate(() => window.got.length)), 1);
    // image-only input never intercepted even when armed
    await page.evaluate(() => document.querySelectorAll('input[type=file]').forEach(i => i.remove()));
    await arm(true);
    await page.evaluate(() => { const i = document.createElement('input'); i.type = 'file'; i.accept = 'image/*'; i.addEventListener('change', () => window.got.push('IMG')); document.body.appendChild(i); i.click(); });
    await page.waitForTimeout(50); assert.equal((await page.evaluate(() => window.got.length)), 1);
    assert.equal((await arm(false)).ok, true);
  } finally {
    await browser.close();
  }
});
