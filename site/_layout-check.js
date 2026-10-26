const PORT = 9333;
const sleep = ms => new Promise(r => setTimeout(r, ms));

async function getTarget() {
  for (let i = 0; i < 60; i++) {
    try {
      const res = await fetch(`http://127.0.0.1:${PORT}/json`);
      const list = await res.json();
      const page = list.find(t => t.type === 'page');
      if (page) return page;
    } catch (e) {}
    await sleep(200);
  }
  throw new Error('Chrome DevTools target not found');
}

async function main() {
  const target = await getTarget();
  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });

  let id = 0;
  const pending = new Map();
  const exceptions = [];
  ws.onmessage = ev => {
    const msg = JSON.parse(ev.data);
    if (msg.id && pending.has(msg.id)) {
      pending.get(msg.id)(msg);
      pending.delete(msg.id);
    } else if (msg.method === 'Runtime.exceptionThrown') {
      exceptions.push(msg.params.exceptionDetails.text || 'exception');
    }
  };

  const send = (method, params = {}) => new Promise((res, rej) => {
    const i = ++id;
    pending.set(i, res);
    ws.send(JSON.stringify({ id: i, method, params }));
    setTimeout(() => {
      if (pending.has(i)) { pending.delete(i); rej(new Error('timeout ' + method)); }
    }, 10000);
  });

  const evaluate = async expression => {
    const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (r.result && r.result.exceptionDetails) {
      throw new Error(JSON.stringify(r.result.exceptionDetails));
    }
    return r.result.result.value;
  };

  await send('Page.enable');
  await send('Runtime.enable');

  const url = process.argv[2];
  const report = {};

  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });
  await send('Page.navigate', { url });
  await sleep(2600);

  report.desktop = await evaluate(`(() => {
    const q = s => document.querySelector(s);
    const rect = s => { const e = q(s); if (!e) return null; const r = e.getBoundingClientRect(); return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) }; };
    return {
      innerWidth,
      scrollWidth: document.documentElement.scrollWidth,
      bodyScrollWidth: document.body.scrollWidth,
      traceLive: !!(q('#trace') && q('#trace').classList.contains('is-live')),
      revealIn: document.querySelectorAll('.reveal.is-in').length,
      revealTotal: document.querySelectorAll('.reveal').length,
      nav: rect('.nav'), hero: rect('.hero'), h1: rect('.hero h1'), trace: rect('#trace'),
      spec: rect('.spec'), modes: rect('#modes'), capGrid: rect('.cap-grid'),
      safety: rect('#safety'), approval: rect('.approval'), start: rect('#start'),
      timeline: rect('.timeline'), footer: rect('.footer'),
      h1Font: getComputedStyle(q('.hero h1')).fontFamily,
      bodyFont: getComputedStyle(document.body).fontFamily,
      h1Size: getComputedStyle(q('.hero h1')).fontSize,
      overflowEls: [...document.querySelectorAll('*')].filter(e => { const r = e.getBoundingClientRect(); return r.right > innerWidth + 1 && r.width > 0; }).slice(0, 10).map(e => e.className || e.tagName)
    };
  })()`);

  await send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
  await send('Page.navigate', { url });
  await sleep(2200);

  report.mobile = await evaluate(`(() => {
    const q = s => document.querySelector(s);
    const rect = s => { const e = q(s); if (!e) return null; const r = e.getBoundingClientRect(); return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) }; };
    return {
      innerWidth,
      scrollWidth: document.documentElement.scrollWidth,
      bodyScrollWidth: document.body.scrollWidth,
      hero: rect('.hero'), trace: rect('#trace'), approval: rect('.approval'),
      navLinks: getComputedStyle(q('.nav-links')).display,
      capGridCols: getComputedStyle(q('.cap-grid')).gridTemplateColumns,
      flowCols: getComputedStyle(q('.flow')).gridTemplateColumns,
      modeCols: getComputedStyle(q('.mode-row')).gridTemplateColumns,
      h1Size: getComputedStyle(q('.hero h1')).fontSize,
      overflowEls: [...document.querySelectorAll('*')].filter(e => { const r = e.getBoundingClientRect(); return r.right > innerWidth + 1 && r.width > 0; }).slice(0, 10).map(e => e.className || e.tagName)
    };
  })()`);

  report.exceptions = exceptions;
  console.log(JSON.stringify(report, null, 2));
  ws.close();
}

main().catch(e => { console.error(e); process.exit(1); });
