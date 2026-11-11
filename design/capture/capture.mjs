// Builds the README GIFs from design/mockups.html.
// Usage: pnpm --dir design/capture install && pnpm --dir design/capture capture
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import { PNG } from 'pngjs';
import gifenc from 'gifenc';

const { GIFEncoder, quantize, applyPalette } = gifenc;
const here = dirname(fileURLToPath(import.meta.url));
const page = resolve(here, '../mockups.html');
const out = resolve(here, '../../docs/media');
const SCALE = 2; // device pixels per CSS pixel; keeps each GIF small
const FRAME_MS = 1600;

// Each GIF is a sequence of phone frames, picked by section id and position (0-based).
const gifs = {
  'keyboard.gif': [['keyboard', 0], ['keyboard', 1], ['keyboard', 2]],
  'dictation.gif': [['dictation', 0], ['dictation', 1], ['dictation', 2]],
  'panels.gif': [['panels', 0], ['panels', 1]],
  'power-button.gif': [['power', 0], ['power', 1], ['power', 2], ['power', 3]],
  'settings.gif': [['settings', 0], ['settings', 1], ['settings', 2], ['settings', 3], ['settings', 4], ['settings', 5]],
};

function encode(pngs) {
  const gif = GIFEncoder();
  for (const buf of pngs) {
    const { width, height, data } = PNG.sync.read(buf);
    const palette = quantize(data, 256);
    gif.writeFrame(applyPalette(data, palette), width, height, { palette, delay: FRAME_MS, repeat: 0 });
  }
  gif.finish();
  return Buffer.from(gif.bytes());
}

mkdirSync(out, { recursive: true });
const browser = await chromium.launch();
const tab = await browser.newPage({ viewport: { width: 1720, height: 1000 }, deviceScaleFactor: SCALE, colorScheme: 'light' });
await tab.goto('file://' + page);
await tab.waitForLoadState('networkidle');
await tab.addStyleTag({ content: '*, *::before, *::after { animation: none !important; transition: none !important; }' });
await tab.evaluate(() => document.fonts.ready);

const shoot = (id, i) => tab.locator(`#${id} figure > .phone`).nth(i).screenshot({ omitBackground: false });

for (const [name, frames] of Object.entries(gifs)) {
  const pngs = [];
  for (const [id, i] of frames) pngs.push(await shoot(id, i));
  const bytes = encode(pngs);
  writeFileSync(resolve(out, name), bytes);
  console.log(`${name}: ${frames.length} frames, ${(bytes.length / 1024).toFixed(0)} KB`);
}

await tab.locator('#tablet .tablet').screenshot({ path: resolve(out, 'tablet-split.png') });
console.log('tablet-split.png');
await browser.close();
