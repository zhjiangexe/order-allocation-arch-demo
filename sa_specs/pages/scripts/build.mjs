import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import MarkdownIt from 'markdown-it';
import YAML from 'yaml';
import { build } from 'esbuild';
import { documents, apiMeta } from '../src/content.mjs';
import { parseFeature, featureMarkdown } from './features.mjs';

const project = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const specs = path.resolve(project, '..');
const out = path.join(project, 'dist');
const escape = (s = '') => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const slug = s => s.toLowerCase().replace(/[^\p{L}\p{N}_\-\s]/gu, '').replace(/\s/g, '-');
const api = YAML.parse(await fs.readFile(path.join(specs, 'openapi.yaml'), 'utf8'));
const attachments = new Map();
const search = [];
const routes = new Map(documents.map(d => [path.join(specs, d.file), `${d.id}.html`]));
routes.set(path.join(specs, 'openapi.yaml'), 'api.html');
const nav = [...documents.filter(d => !d.hidden), apiMeta].sort((a, b) => Number(a.number ?? 0) - Number(b.number ?? 0));
const icon = name => {
  const drawings = {
    search: '<circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 4.5 4.5"/>',
    menu: '<path d="M4 6h16M4 12h16M4 18h16"/>',
    close: '<path d="m6 6 12 12M18 6 6 18"/>',
    expand: '<path d="M9 3H3v6m12-6h6v6M3 15v6h6m12-6v6h-6"/>',
    download: '<path d="M12 3v12m-5-5 5 5 5-5M4 17v4h16v-4"/>',
  };
  return `<svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${drawings[name]}</svg>`;
};
function shell(meta, body, { toc = [], source = '' } = {}) {
  const title = `${meta.number ? `${meta.number}｜` : ''}${meta.title}`;
  return `<!doctype html>
<html lang="zh-Hant"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><meta name="theme-color" content="#f1f3f5"><title>${escape(title)} · Archone</title><meta name="description" content="Archone 系統分析文件：業務流程、整合、資料設計與驗收。"><link rel="icon" href="assets/favicon.svg" type="image/svg+xml"><link rel="stylesheet" href="assets/bootstrap.min.css">${meta.id === 'api' ? '<link rel="stylesheet" href="assets/swagger-ui/swagger-ui.css">' : ''}<link rel="stylesheet" href="assets/site.css"><script type="module" src="assets/app.js"></script>${meta.id === 'api' ? '<script defer src="assets/swagger-ui/swagger-ui-bundle.js"></script><script defer src="assets/openapi.js"></script>' : ''}</head>
<body data-page="${meta.id}"><a class="skip-link" href="#main-content">跳至主要內容</a><div class="menu-backdrop" data-menu-close hidden></div>
<aside class="sidebar" id="site-navigation" aria-label="主要導覽"><div class="sidebar-brand"><a class="brand" href="index.html">Archone</a><button class="icon-button mobile-only" data-menu-close aria-label="關閉導覽">${icon('close')}</button></div><nav class="nav nav-pills flex-column">${nav.map(d => `<a class="nav-link ${(meta.parent ?? meta.id) === d.id ? 'active is-active' : ''}" ${(meta.parent ?? meta.id) === d.id ? 'aria-current="page"' : ''} href="${d.id}.html">${d.number ? `<span class="nav-number">${d.number}</span>` : ''}<span>${d.title}</span></a>`).join('')}</nav></aside>
<div class="workspace"><header class="topbar"><button class="menu-button mobile-only" data-menu-open aria-controls="site-navigation" aria-expanded="false" aria-label="開啟導覽">${icon('menu')}目錄</button><button class="search-trigger btn btn-outline-secondary" data-search-open>${icon('search')}<span>搜尋文件</span><kbd>⌘ K</kbd></button></header>
<main id="main-content" tabindex="-1" class="main-content"><header class="page-heading"><h1>${escape(meta.id === 'index' ? 'Archone 系統分析文件' : title)}</h1></header>
${toc.length && meta.id !== 'index' ? `<details class="page-toc"><summary>本頁章節</summary><nav aria-label="本頁章節">${toc.map(h => `<a class="toc-level-${h.level}" href="#${escape(h.id)}">${escape(h.text)}</a>`).join('')}</nav></details>` : ''}
${body}
<footer class="page-footer">${source ? `<a href="${source}" download>下載 Markdown</a>` : '<span></span>'}<a href="#main-content">回到頁首 ↑</a></footer></main></div>
<dialog class="search-dialog" aria-labelledby="search-title"><div class="search-dialog-head"><span id="search-title">搜尋文件</span><button class="icon-button" data-search-close aria-label="關閉搜尋">${icon('close')}</button></div><label class="search-field">${icon('search')}<input id="search-input" type="search" placeholder="輸入關鍵字" autocomplete="off" aria-label="搜尋關鍵字"></label><div id="search-results" role="status" aria-live="polite"><p class="empty-state">輸入關鍵字搜尋章節。</p></div></dialog>
<dialog class="image-viewer" aria-labelledby="viewer-title"><header class="viewer-toolbar"><h2 id="viewer-title">圖表檢視</h2><div class="viewer-controls"><button data-zoom-out aria-label="縮小">−</button><output id="zoom-level" aria-live="polite">100%</output><button data-zoom-in aria-label="放大">＋</button><button data-zoom-reset>適合畫面</button><a id="viewer-download" download aria-label="下載圖檔">${icon('download')}</a><button data-viewer-close aria-label="關閉圖片">${icon('close')}</button></div></header><div class="viewer-stage" tabindex="0" aria-label="圖片檢視區，可拖曳平移或用方向鍵移動"><img class="viewer-image" alt=""></div><p class="viewer-hint">拖曳平移 · 滾輪或雙指縮放 · Esc 關閉</p></dialog>
<div class="toast" role="status" aria-live="polite"></div></body></html>`;
}
async function emit(name, content) { await fs.writeFile(path.join(out, name), content); }
function download(file) {
  const absolute = path.resolve(file);
  const relative = path.relative(specs, absolute);
  if (relative.startsWith('..') || path.isAbsolute(relative)) throw new Error(`Outside specifications: ${file}`);
  const url = `downloads/${relative.split(path.sep).map(encodeURIComponent).join('/')}`;
  attachments.set(absolute, url);
  return url;
}
function link(source, url) {
  if (/^(https?:|mailto:|#)/.test(url)) return url;
  const [name, fragment] = url.split('#');
  const target = path.resolve(path.dirname(source), decodeURIComponent(name));
  const route = routes.get(target);
  if (route) return route + (fragment ? `#${fragment}` : '');
  return download(target) + (fragment ? `#${fragment}` : '');
}
function renderDocument(source, text, meta) {
  const toc = []; const used = new Map(); const sections = [];
  const md = new MarkdownIt({ html: true, linkify: true });
  const opening = md.renderer.rules.link_open ?? ((tokens, idx, opts, env, renderer) => renderer.renderToken(tokens, idx, opts));
  md.renderer.rules.link_open = (tokens, idx, opts, env, renderer) => {
    const token = tokens[idx]; const href = link(source, token.attrGet('href')); token.attrSet('href', href);
    if (/^https?:/.test(href)) { token.attrSet('target', '_blank'); token.attrSet('rel', 'noopener noreferrer'); }
    if (tokens[idx + 1]?.content?.startsWith('下載')) token.attrSet('download', '');
    else if (/\.(svg|png|jpe?g|webp)(?:#.*)?$/i.test(href)) token.attrSet('data-image-link', '');
    return opening(tokens, idx, opts, env, renderer);
  };
  md.renderer.rules.image = (tokens, idx) => {
    const src = link(source, tokens[idx].attrGet('src')); const title = tokens[idx].content;
    return `<button class="process-image" data-image="${escape(src)}" data-title="${escape(title)}" aria-label="放大${escape(title)}"><img src="${escape(src)}" alt="${escape(title)}" loading="lazy" decoding="async"><span>${icon('expand')}放大圖表</span></button>`;
  };
  md.renderer.rules.heading_open = (tokens, idx) => {
    const text = tokens[idx + 1].content.replace(/`/g, ''); const base = slug(text);
    const count = used.get(base) ?? 0; used.set(base, count + 1); const id = `${base}${count ? `-${count}` : ''}`;
    const level = Number(tokens[idx].tag.slice(1));
    if (level <= 3) toc.push({ id, text, level });
    return `<${tokens[idx].tag} id="${escape(id)}">`;
  };
  md.renderer.rules.table_open = () => '<div class="table-scroll" tabindex="0" role="region" aria-label="資料表格，可橫向捲動"><table class="table table-striped table-hover mb-0">';
  md.renderer.rules.table_close = () => '</table></div>';
  md.renderer.rules.fence = (tokens, idx) => {
    const { content, info } = tokens[idx];
    if (info.trim() === 'mermaid') return `<figure class="diagram"><div class="diagram-top"><button class="diagram-expand" data-diagram-open disabled>${icon('expand')}放大圖表</button></div><div class="diagram-preview"><pre class="mermaid-source" hidden>${escape(content)}</pre><p class="muted">載入圖表…</p></div></figure>`;
    return `<div class="code-block"><div class="code-header"><span>${escape(info || 'TEXT')}</span><button data-copy-code>複製</button></div><pre><code>${escape(content)}</code></pre></div>`;
  };
  const stripped = text.replace(/^# .+\n/, '');
  const tokens = md.parse(stripped, {});
  const html = md.renderer.render(tokens, md.options, {});
  // Search uses the exact heading IDs rendered above, including duplicate suffixes.
  let section = { title: meta.title, page: meta.title, url: `${meta.id}.html`, content: '' };
  let headingIndex = 0;
  for (let i = 0; i < tokens.length; i++) {
    const token = tokens[i];
    if (token.type === 'heading_open' && Number(token.tag.slice(1)) <= 3) {
      if (section.content.trim()) sections.push(section);
      const heading = toc[headingIndex++];
      section = { title: heading.text, page: meta.title, url: `${meta.id}.html#${heading.id}`, content: '' };
    }
    if (token.type === 'inline') section.content += (token.children ?? [token]).filter(child => child.type !== 'html_inline').map(child => child.content).join('') + ' ';
  }
  if (section.content.trim()) sections.push(section);
  search.push(...sections);
  return { html, toc };
}
function apiPage() {
  const anchors = Object.values(api.paths).flatMap(methods => Object.entries(methods)
    .filter(([method]) => /^(get|post|put|patch|delete)$/.test(method))
    .map(([, op]) => `<span hidden id="${escape(op.operationId)}" data-openapi-tag="${escape(op.tags?.[0] ?? 'default')}"></span>`));
  return `<article class="prose"><h2 id="method-scope">1 介面範圍</h2><p class="api-description">${escape(api.info.description)}</p><p><a class="text-link" href="${download(path.join(specs, 'openapi.yaml'))}" download>下載 openapi.yaml</a></p><h2 id="endpoints">2 端點與資料結構</h2></article>${anchors.join('')}<p class="muted api-load-status" role="status">載入 API 文件…</p><div id="swagger-ui"></div><noscript><p>請啟用 JavaScript，或下載上方的 openapi.yaml 閱讀完整規格。</p></noscript>`;
}

function pagination(id) {
  const index = nav.findIndex(item => item.id === id);
  return index < 0 ? '' : `<nav class="page-pagination" aria-label="相鄰文件">${index > 0 ? `<a href="${nav[index - 1].id}.html">← ${nav[index - 1].number ?? ''} ${nav[index - 1].title}</a>` : '<span></span>'}${index + 1 < nav.length ? `<a href="${nav[index + 1].id}.html">${nav[index + 1].number} ${nav[index + 1].title} →</a>` : '<span></span>'}</nav>`;
}

await fs.rm(out, { recursive: true, force: true });
await fs.mkdir(path.join(out, 'assets'), { recursive: true });
await build({ entryPoints: [path.join(project, 'src/app.js')], bundle: true, splitting: true, format: 'esm', outdir: path.join(out, 'assets'), entryNames: 'app', chunkNames: 'chunks/[name]-[hash]', minify: true, target: ['es2022'], logLevel: 'warning' });
await fs.copyFile(path.join(project, 'src/site.css'), path.join(out, 'assets/site.css'));
await fs.mkdir(path.join(out, 'assets/swagger-ui'), { recursive: true });
for (const file of ['swagger-ui.css', 'swagger-ui-bundle.js', 'swagger-ui-bundle.js.LICENSE.txt', 'LICENSE', 'NOTICE']) {
  await fs.copyFile(path.join(project, 'node_modules/swagger-ui-dist', file), path.join(out, 'assets/swagger-ui', file));
}
await fs.copyFile(path.join(project, 'src/openapi.js'), path.join(out, 'assets/openapi.js'));
await emit('assets/openapi.json', JSON.stringify(api));
await fs.copyFile(path.join(project, 'node_modules/bootstrap/dist/css/bootstrap.min.css'), path.join(out, 'assets/bootstrap.min.css'));
await fs.copyFile(path.join(project, 'node_modules/bootstrap/LICENSE'), path.join(out, 'assets/bootstrap-LICENSE.txt'));
await fs.writeFile(path.join(out, 'assets/favicon.svg'), '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 40 40"><rect width="40" height="40" rx="6" fill="#333"/><path d="M10 30 20 10l10 20h-7l-3-7-3 7Z" fill="#fff"/></svg>');
for (const doc of documents) {
  const source = path.join(specs, doc.file);
  let text = await fs.readFile(source, 'utf8');
  if (doc.id === 'bdd') {
    const markers = [...text.matchAll(/<!-- feature: ([\w-]+\.feature) -->/g)];
    for (const [index, marker] of markers.entries()) {
      const file = path.join(specs, 'biz-bdd', marker[1]);
      const feature = parseFeature(await fs.readFile(file, 'utf8'), marker[1]);
      text = text.replace(marker[0], featureMarkdown(feature, index + 1));
    }
  }
  let { html, toc } = renderDocument(source, text, doc);
  if (doc.id === 'bdd') html = html.replace(/<strong>(Given|When|Then|And|But)<\/strong>/g, '<strong class="gherkin-keyword">$1</strong>');
  const adjacent = pagination(doc.id);
  await emit(`${doc.id}.html`, shell(doc, `<article class="prose">${html}</article>${doc.id === 'index' ? '' : adjacent}`, { toc, source: download(source) }));
}
await emit('api.html', shell(apiMeta, `${apiPage()}${pagination(apiMeta.id)}`, { toc: [{ id: 'method-scope', text: '1 介面範圍', level: 2 }, { id: 'endpoints', text: '2 端點與資料結構', level: 2 }] }));
for (const [route, methods] of Object.entries(api.paths)) for (const [method, op] of Object.entries(methods)) {
  if (/^(get|post|put|patch|delete)$/.test(method)) search.push({ title: `${method.toUpperCase()} ${route}`, page: apiMeta.title, url: `api.html#${op.operationId}`, content: `${op.summary} ${op.description ?? ''}` });
}
for (const [file, url] of attachments) { const dest = path.join(out, decodeURIComponent(url)); await fs.mkdir(path.dirname(dest), { recursive: true }); await fs.copyFile(file, dest); }
await emit('404.html', shell({ id: '404', title: '找不到這個頁面' }, '<p>連結可能已變更，請從<a href="/">專案導覽</a>重新開始。</p>').replace('<head>', '<head><base href="/">'));
await fs.writeFile(path.join(out, 'assets/search-index.json'), JSON.stringify(search));
await emit('_headers', '/*\n  X-Content-Type-Options: nosniff\n  Referrer-Policy: strict-origin-when-cross-origin\n  X-Frame-Options: DENY\n  Permissions-Policy: camera=(), microphone=(), geolocation=()\n/assets/chunks/*\n  Cache-Control: public, max-age=31536000, immutable\n');
await emit('robots.txt', 'User-agent: *\nAllow: /\n');
console.log(`Built ${documents.length + 2} pages, ${attachments.size} downloads and ${search.length} search sections → dist/`);
