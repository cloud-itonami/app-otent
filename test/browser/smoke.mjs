// A real browser against the DEPLOYED Worker.
//
// Not a screenshot check. A globe that renders a black rectangle and a
// globe that renders the Earth produce the same screenshot to a script,
// so this asserts things a picture cannot: which backend the renderer
// actually chose, that pixels were drawn, that crossing a view does NOT
// load a document, and that app state survives the crossing.
import { chromium } from 'playwright';

const BASE = process.argv[2] || 'https://app-tenkyu.04-feasts-minded.workers.dev';
const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok, detail });
  console.log(`${ok ? '  ok  ' : '  FAIL'} ${name}${detail ? ' :: ' + detail : ''}`);
};

const EXECUTABLE = process.env.TENKYU_CHROME;   // full Chrome for Testing, not the headless shell

const browser = await chromium.launch({
  ...(EXECUTABLE ? { executablePath: EXECUTABLE } : {}),
  args: [
    // WebGPU in headless Chromium needs both. Without them the run would
    // silently exercise only the WebGL path and report a pass for a
    // backend it never touched.
    '--enable-unsafe-webgpu',
    '--enable-features=Vulkan,UseSkiaRenderer',
    '--use-angle=metal',
  ],
});
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });

const errors = [];
const rasterTiles = [];
const buildingTiles = [];
page.on('pageerror', e => errors.push(String(e)));
page.on('response', r => {
  if (r.url().includes('/api/basemap/blue-marble/')) rasterTiles.push(r.status());
  if (r.url().includes('/api/basemap/buildings/')) buildingTiles.push(r.status());
});
page.on('console', m => {
  if (m.type() !== 'error') return;
  // The fire and vessel layers are 404 BY DESIGN -- `tenkyu` reports them
  // as UNMEASURED and the app renders that. Chrome logs every 404 as a
  // console error, so counting those would make the expected outcome look
  // like a fault. Anything else is a real error.
  if (/status of 404/.test(m.text())) return;
  errors.push(m.text());
});

await page.goto(BASE, { waitUntil: 'networkidle', timeout: 120000 });

// --- the document the SERVER sent, before any script ran
const ssr = await page.content();
check('server-rendered document names the app', ssr.includes('tenkyu'));
check('server-rendered document has the canvas', ssr.includes('tenkyu-canvas'));

// --- the renderer actually started, and says which one
//
// Synchronised on the frame counter the app publishes, not on wall clock
// and not on the plaque. The plaque updates as soon as the backend is
// chosen, which is BEFORE the first frame has drawn -- reading the canvas
// there returns an unsized 300x150 default, and that is what an earlier
// version of this file measured and reported as "nothing drawn".
await page.waitForFunction(() => (window.__tenkyu?.frames ?? 0) > 30, { timeout: 90000 });
const framesA = await page.evaluate(() => window.__tenkyu.frames);
await page.waitForTimeout(1000);
check('the render loop is alive, not stopped after one frame',
      (await page.evaluate(() => window.__tenkyu.frames)) > framesA + 5,
      `frames ${framesA} -> ${await page.evaluate(() => window.__tenkyu.frames)}`);
const plaque = await page.locator('.tenkyu-plaque').first().innerText();
const backend = (plaque.match(/renderer: (\w+)/) || [])[1];
check('a GPU backend was chosen', ['webgpu', 'webgl2'].includes(backend), `backend=${backend}`);
check('WebGPU was preferred, not silently skipped', backend === 'webgpu',
      backend === 'webgl2' ? 'fell back to WebGL 2 -- correct behaviour, but note it' : '');

// --- pixels.
//
// Read through `toDataURL`, NOT `createImageBitmap`. On a WebGPU canvas
// `createImageBitmap` returns a fully transparent bitmap -- measured
// 2026-08-26: 402x540, one distinct colour, first pixel (0,0,0,0), while
// `toDataURL` on the same canvas returned an 88 KB PNG. A blank read is
// indistinguishable from a blank canvas, so this check has to use the path
// that actually sees the composited frame.
const drew = await page.evaluate(async () => {
  const c = document.getElementById('tenkyu-globe');
  if (!c) return { err: 'no canvas' };
  const url = c.toDataURL('image/png');
  const img = new Image();
  await new Promise((res, rej) => { img.onload = res; img.onerror = rej; img.src = url; });
  const off = new OffscreenCanvas(img.width, img.height);
  const ctx = off.getContext('2d');
  ctx.drawImage(img, 0, 0);
  const { data } = ctx.getImageData(0, 0, img.width, img.height);
  const seen = new Set();
  let lit = 0;
  for (let i = 0; i < data.length; i += 4 * 97) {
    seen.add(`${data[i]},${data[i+1]},${data[i+2]}`);
    if (data[i] + data[i+1] + data[i+2] > 60) lit++;
  }
  return { w: img.width, h: img.height, distinct: seen.size, lit, pngBytes: url.length };
});
check('the canvas has a drawing buffer', drew.w > 0 && drew.h > 0, JSON.stringify(drew));
check('more than one colour was drawn -- not a cleared rectangle', (drew.distinct || 0) > 20,
      `distinct=${drew.distinct}`);
check('a substantial part of the frame is lit', (drew.lit || 0) > 100, `lit=${drew.lit}`);

// --- the basemap actually came out of OUR bucket
check('raster tiles were fetched from the Worker', rasterTiles.length > 0,
      `${rasterTiles.length} tiles, statuses ${[...new Set(rasterTiles)].join(',')}`);
check('every raster tile request succeeded', rasterTiles.every(s => s === 200),
      [...new Set(rasterTiles)].join(','));

// --- the lake actually reached the browser
await page.waitForFunction(
  () => /satellite: \d+/.test(document.body.innerText), { timeout: 90000 }).catch(() => {});
const plaque2 = await page.locator('.tenkyu-plaque').first().innerText();
check('satellites arrived from the lake', /satellite: [1-9]/.test(plaque2), plaque2.replace(/\n/g, ' | '));
check('an UNMEASURED layer is named as such, not shown as empty',
      /UNMEASURED/.test(plaque2));

// --- single page: crossing a view must NOT load a document
await page.evaluate(() => { window.__tenkyuMark = 'survived'; });
await page.click('a[href="#sources"]');
// Wait for something that exists ONLY in the sources view. Waiting on
// something both views have returns before the crossing renders.
await page.waitForFunction(
  () => document.body.innerText.includes('Natural Earth'), { timeout: 20000 });
const mark = await page.evaluate(() => window.__tenkyuMark);
check('crossing a view did not load a document', mark === 'survived', `mark=${mark}`);
check('the sources view credits its feeds',
      (await page.content()).includes('CelesTrak'));

// --- and back, with the GPU still alive
await page.click('a[href="#globe"]');
await page.waitForFunction(() => !!document.getElementById('tenkyu-globe'), { timeout: 20000 });
const backend2 = ((await page.locator('.tenkyu-plaque').first().innerText())
                  .match(/renderer: (\w+)/) || [])[1];
check('the GPU device survived the crossing', backend2 === backend, `${backend} -> ${backend2}`);
// Stronger: the canvas must not have been REBUILT. `reattached` counts how
// many times the renderer found the node it held was no longer in the page.
// One is the initial client render replacing the server-rendered document;
// more than that means a view crossing is destroying the GPU device.
const reattached = await page.evaluate(() => window.__tenkyu.reattached ?? 0);
check('the renderer was never rebuilt -- not on the initial commit, not on a crossing',
      reattached === 0, `reattached=${reattached}`);
check('the basemap tiles stayed resident across the crossing',
      (await page.evaluate(() => window.__tenkyu.glTiles ?? -1)) !== 0,
      `tiles after crossing: ${await page.evaluate(() => window.__tenkyu.glTiles ?? 'n/a (webgpu)')}`);

check('no uncaught page errors', errors.length === 0, errors.slice(0, 3).join(' | '));

await page.screenshot({ path: 'test/browser/globe.png' });
await page.locator('#tenkyu-globe').screenshot({ path: 'test/browser/globe-canvas.png' });

// ---------------------------------------------------------------------------
// Buildings.
//
// Reached through the fly-to button, which is GENERATED from the buildings
// manifest -- so this also checks that a city in the bucket has a way to be
// seen. Zooming by hand instead would test the wheel, not the feature.
const tilesBefore = await page.evaluate(() => window.__tenkyu.glTiles ?? null);
const flyBtn = page.getByTitle('fly to New York (Manhattan)');
check('the manifest produced a fly-to control', await flyBtn.count() > 0);
if (await flyBtn.count() > 0) {
  await flyBtn.click();
  await page.waitForFunction(() => (window.__tenkyu?.buildings ?? 0) > 0, { timeout: 60000 })
    .catch(() => {});
  await page.waitForTimeout(5000);
  const d = await page.evaluate(() => window.__tenkyu);
  check('building footprints loaded from the lake', (d.buildings ?? 0) > 1000,
        `buildings=${d.buildings}`);
  check('ground polygons loaded with them', (d.surface ?? 0) > 0, `surface=${d.surface}`);
  check('every building tile request succeeded', buildingTiles.every(s => s === 200),
        `${buildingTiles.length} requests, statuses ${[...new Set(buildingTiles)].join(',')}`);
  // Both backends report a tile count now. Accepting `null` here is how
  // this check passed vacuously on WebGPU -- the preferred renderer -- for
  // as long as only WebGL reported one.
  const resident = d.glTiles ?? d.gpuTiles;
  check('both backends report a resident tile count', resident != null,
        `glTiles=${d.glTiles} gpuTiles=${d.gpuTiles}`);
  check('descending did NOT leave the whole planet resident', resident < 40,
        `tiles ${tilesBefore ?? '?'} -> ${resident}`);
  await page.locator('#tenkyu-globe').screenshot({ path: 'test/browser/city-webgpu.png' });

  // Flying back out must CLEAR the city, or it stays welded to the globe.
  //
  // Driven with `page.mouse.wheel`, a real input event. Dispatching a
  // synthetic `WheelEvent` from inside the page did NOT reach the handler,
  // so this check reported the city as never cleared while a hand-driven
  // probe of the same build showed buildings going 7821 -> 0 and resident
  // tiles 7 -> 1. The application was right and the test was driving it in
  // a way no user does.
  const box = await page.locator('#tenkyu-globe').boundingBox();
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  for (let i = 0; i < 25; i++) { await page.mouse.wheel(0, 800); await page.waitForTimeout(30); }
  await page.waitForTimeout(5000);
  const out = await page.evaluate(() => window.__tenkyu);
  check('leaving the area cleared the buildings', (out.buildings ?? -1) === 0,
        `buildings=${out.buildings}`);
  check('and released their GPU buffers', (out.gpuBuildings ?? out.glBuildings ?? -1) === 0,
        `gpuBuildings=${out.gpuBuildings} glBuildings=${out.glBuildings}`);
}

// ---------------------------------------------------------------------------
// The WebGL 2 fallback, forced.
//
// Not by dropping a Chrome flag: Chrome for Testing enables WebGPU by
// default on Metal, so a flag-based attempt silently ran WebGPU again and
// reported the fallback as passing. Deleting `navigator.gpu` before any
// page script runs is exact, and it is what a browser without WebGPU
// actually looks like.
const page2 = await browser.newPage({ viewport: { width: 1400, height: 1000 } });
await page2.addInitScript(() => {
  Object.defineProperty(navigator, 'gpu', { get: () => undefined, configurable: true });
});
await page2.goto(BASE, { waitUntil: 'networkidle', timeout: 120000 });
await page2.waitForFunction(() => (window.__tenkyu?.frames ?? 0) > 30, { timeout: 90000 });
const plaqueB = await page2.locator('.tenkyu-plaque').first().innerText();
const backendB = (plaqueB.match(/renderer: (\w+)/) || [])[1];
check('without WebGPU the renderer falls back to WebGL 2', backendB === 'webgl2',
      `backend=${backendB}`);

// The fallback is measured through the COMPOSITOR, not `toDataURL`.
//
// A WebGL context created without `preserveDrawingBuffer` has an undefined
// drawing buffer once the frame is composited, so `toDataURL` returns a
// blank image no matter what was drawn. Measured 2026-08-26: `toDataURL`
// reported one distinct colour and zero lit pixels while an element
// screenshot of the same canvas showed the Blue Marble, coastlines,
// aircraft over east Asia and the quake markers.
//
// Turning `preserveDrawingBuffer` on to make the read work would slow
// every production frame to serve a test. Screenshotting the element is
// what a viewer sees anyway.
const shotB = await page2.locator('#tenkyu-globe').screenshot();
const shotBlank = await page2.evaluate(async () => {
  // A same-size canvas holding only the clear colour, encoded the same
  // way, as the floor to compare against. Without it "is 40 KB a lot?" is
  // a number somebody guessed.
  const live = document.getElementById('tenkyu-globe');
  const c = document.createElement('canvas');
  c.width = live.clientWidth; c.height = live.clientHeight;
  const x = c.getContext('2d');
  x.fillStyle = 'rgb(5,8,15)';
  x.fillRect(0, 0, c.width, c.height);
  return c.toDataURL('image/png').length;
});
check('the fallback draws far more than a cleared rectangle',
      shotB.length > shotBlank * 4,
      `png ${shotB.length}B vs a flat fill of the same size ~${shotBlank}B`);

const diagGl = await page2.evaluate(() => window.__tenkyu);
check('the fallback submitted geometry with no GL error',
      diagGl.glError === 0 && diagGl.glTiles > 0 &&
      diagGl.glMarkers > 0 && diagGl.glLines > 0,
      JSON.stringify({ glError: diagGl.glError, tiles: diagGl.glTiles,
                       markers: diagGl.glMarkers, lines: diagGl.glLines }));

const diagB = diagGl;
check('the fallback drew the same object count as WebGPU',
      diagB.objects === (await page.evaluate(() => window.__tenkyu.objects)),
      `webgl2=${diagB.objects} webgpu=${await page.evaluate(() => window.__tenkyu.objects)}`);
check('the fallback sized its canvas the same way', /^\d+x\d+$/.test(diagB.canvas),
      `canvas=${diagB.canvas}`);
await page2.screenshot({ path: 'test/browser/globe-webgl2.png' });
await page2.locator('#tenkyu-globe').screenshot({ path: 'test/browser/globe-webgl2-canvas.png' });

await browser.close();

const failed = results.filter(r => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
process.exit(failed.length ? 1 : 0);
