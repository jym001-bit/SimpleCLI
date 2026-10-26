const fs = require('fs');
const html = fs.readFileSync('site/index.html', 'utf8');
const voidTags = new Set(['area','base','br','col','embed','hr','img','input','link','meta','param','source','track','wbr']);
const stack = [];
const errors = [];
const clean = html
  .replace(/<!--[\s\S]*?-->/g, '')
  .replace(/<script[\s\S]*?<\/script>/gi, '<script></script>')
  .replace(/<style[\s\S]*?<\/style>/gi, '<style></style>');
const re = /<(\/?)([a-zA-Z][\w:-]*)([^>]*?)(\/?)>/g;
let m;
while ((m = re.exec(clean))) {
  const closing = m[1] === '/';
  const tag = m[2].toLowerCase();
  const selfClosing = m[4] === '/' || voidTags.has(tag);
  if (closing) {
    if (selfClosing) continue;
    if (!stack.length) { errors.push(`unexpected </${tag}> near ${m.index}`); continue; }
    if (stack[stack.length - 1].tag === tag) stack.pop();
    else {
      errors.push(`mismatch </${tag}> near ${m.index}, open <${stack[stack.length - 1].tag}>`);
      const idx = stack.map(x => x.tag).lastIndexOf(tag);
      if (idx >= 0) stack.length = idx;
    }
  } else if (!selfClosing) {
    stack.push({ tag, index: m.index });
  }
}
const style = (html.match(/<style>([\s\S]*?)<\/style>/) || [,''])[1];
const ids = [...html.matchAll(/\sid="([^"]+)"/g)].map(x => x[1]);
const dupIds = ids.filter((id, i) => ids.indexOf(id) !== i);
const copies = [...html.matchAll(/data-copy="([^"]*)"/g)].map(x => x[1]);
console.log('chars:', html.length);
console.log('tag errors:', errors.length ? errors.slice(0, 10) : 'none');
console.log('unclosed:', stack.length ? stack.slice(-10) : 'none');
console.log('css brace balance:', (style.match(/{/g) || []).length - (style.match(/}/g) || []).length);
console.log('ids:', ids.join(', '));
console.log('duplicate ids:', dupIds.length ? dupIds : 'none');
console.log('copy targets:', copies.length, JSON.stringify(copies));
console.log('h1:', (html.match(/<h1/g) || []).length, 'h2:', (html.match(/<h2/g) || []).length);
console.log('anchors:', [...html.matchAll(/href="#([^"]+)"/g)].map(x => x[1]).join(', '));
const js = (html.match(/<script>([\s\S]*?)<\/script>/) || [,''])[1];
try { new Function(js); console.log('js syntax: ok'); } catch (e) { console.log('js syntax error:', e.message); }
