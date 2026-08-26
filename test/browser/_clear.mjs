import { chromium } from 'playwright';
const b = await chromium.launch({ executablePath: process.env.TENKYU_CHROME, args:['--use-angle=metal'] });
const p = await b.newPage({ viewport:{width:1400,height:1000} });
await p.goto('https://app-tenkyu.04-feasts-minded.workers.dev',{waitUntil:'networkidle',timeout:120000});
await p.waitForFunction(()=> (window.__tenkyu?.frames??0)>30,{timeout:90000});
console.log('start   :', JSON.stringify(await p.evaluate(()=>window.__tenkyu)));
await p.getByTitle('fly to New York (Manhattan)').click();
await p.waitForFunction(()=> (window.__tenkyu?.buildings??0)>0,{timeout:60000}).catch(()=>{});
await p.waitForTimeout(4000);
console.log('at city :', JSON.stringify(await p.evaluate(()=>window.__tenkyu)));
// scroll out via real wheel, with the mouse over the canvas
const box = await p.locator('#tenkyu-globe').boundingBox();
await p.mouse.move(box.x+box.width/2, box.y+box.height/2);
for (let i=0;i<25;i++) { await p.mouse.wheel(0, 800); await p.waitForTimeout(30); }
await p.waitForTimeout(5000);
console.log('zoomed  :', JSON.stringify(await p.evaluate(()=>window.__tenkyu)));
console.log('camera  :', JSON.stringify(await p.evaluate(()=>{
  const t=[...document.querySelectorAll('.tenkyu-plaque')].map(e=>e.innerText);
  return t.find(x=>x.includes('km up'))||t;
})));
await b.close();
