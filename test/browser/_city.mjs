import { chromium } from 'playwright';
const b = await chromium.launch({ executablePath: process.env.OTENT_CHROME, args:['--use-angle=metal'] });
for (const noGpu of [false, true]) {
  const p = await b.newPage({ viewport:{width:1400,height:1000} });
  if (noGpu) await p.addInitScript(()=>{Object.defineProperty(navigator,'gpu',{get:()=>undefined,configurable:true});});
  const errs=[]; p.on('pageerror',e=>errs.push(e.message));
  const reqs=[]; p.on('response',r=>{ if(r.url().includes('/buildings/')) reqs.push(r.status()); });
  await p.goto('https://app-otent.04-feasts-minded.workers.dev',{waitUntil:'networkidle',timeout:120000});
  await p.waitForFunction(()=> (window.__otent?.frames??0)>30,{timeout:90000});
  const before = await p.evaluate(()=>window.__otent);
  // Click the Manhattan fly-to button (generated from the manifest).
  await p.getByTitle('fly to New York (Manhattan)').click();
  await p.waitForFunction(()=> (window.__otent?.buildings??0) > 0, {timeout:60000})
    .catch(()=>{});
  await p.waitForTimeout(6000);
  const after = await p.evaluate(()=>window.__otent);
  console.log(noGpu?'--- WebGL 2 ---':'--- WebGPU ---');
  console.log('  before fly:', JSON.stringify({tiles: before.glTiles, buildings: before.buildings}));
  console.log('  after  fly:', JSON.stringify(after));
  console.log('  building tile requests:', reqs.length, [...new Set(reqs)].join(',')||'-');
  console.log('  errors:', errs.slice(0,2).join(' | ')||'none');
  await p.locator('#otent-globe').screenshot({path:`test/browser/city-${noGpu?'webgl2':'webgpu'}.png`});
  await p.close();
}
await b.close();
