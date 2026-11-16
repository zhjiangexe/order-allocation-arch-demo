const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
const sidebar = $('.sidebar');
const menuButton = $('[data-menu-open]');
const backdrop = $('.menu-backdrop');
const mobile = matchMedia('(max-width: 900px)');
function closeMenu(returnFocus = true) {
  document.body.classList.remove('menu-open');
  $('.workspace').inert = false;
  backdrop.hidden = true;
  menuButton.setAttribute('aria-expanded', 'false');
  sidebar.inert = mobile.matches;
  if (returnFocus && mobile.matches) menuButton.focus();
}
function openMenu() {
  document.body.classList.add('menu-open');
  sidebar.inert = false;
  backdrop.hidden = false;
  menuButton.setAttribute('aria-expanded', 'true');
  $('.workspace').inert = true;
  $('[data-menu-close]', sidebar).focus();
}
menuButton.addEventListener('click', openMenu);
$$('[data-menu-close]').forEach(button => button.addEventListener('click', () => closeMenu()));
mobile.addEventListener('change', () => closeMenu(false));
closeMenu(false);
document.addEventListener('keydown', event => {
  if (!document.body.classList.contains('menu-open')) return;
  if (event.key === 'Escape') closeMenu();
  if (event.key === 'Tab') {
    const focusable = $$('a,button', sidebar).filter(el => el.getClientRects().length);
    if (event.shiftKey && document.activeElement === focusable[0]) { event.preventDefault(); focusable.at(-1).focus(); }
    if (!event.shiftKey && document.activeElement === focusable.at(-1)) { event.preventDefault(); focusable[0].focus(); }
  }
});
let toastTimer;
function toast(message) { $('.toast').textContent = message; $('.toast').classList.add('visible'); clearTimeout(toastTimer); toastTimer = setTimeout(() => $('.toast').classList.remove('visible'), 2200); }
$$('[data-copy-code]').forEach(button => button.addEventListener('click', async () => {
  try { await navigator.clipboard.writeText($('pre', button.closest('.code-block')).textContent); toast('已複製到剪貼簿'); }
  catch { toast('無法使用剪貼簿，請選取程式碼複製。'); }
}));
function modal(dialog, closeSelector) {
  let trigger;
  const open = () => { trigger = document.activeElement; dialog.showModal(); document.body.classList.add('dialog-open'); };
  $(closeSelector, dialog).addEventListener('click', () => dialog.close());
  dialog.addEventListener('click', event => { if (event.target === dialog) { const r = dialog.getBoundingClientRect(); if (event.clientX < r.left || event.clientX > r.right || event.clientY < r.top || event.clientY > r.bottom) dialog.close(); } });
  dialog.addEventListener('close', () => { document.body.classList.remove('dialog-open'); trigger?.focus(); });
  return open;
}
const searchDialog = $('.search-dialog');
const showSearch = modal(searchDialog, '[data-search-close]');
const searchInput = $('#search-input');
let searchIndex;
async function openSearch() {
  if (document.body.classList.contains('menu-open')) closeMenu(false);
  showSearch(); searchInput.focus();
  try {
    searchIndex ??= fetch('assets/search-index.json').then(response => { if (!response.ok) throw Error('Search unavailable'); return response.json(); });
    await searchIndex; await search();
  } catch { $('#search-results').textContent = '搜尋索引暫時無法載入，請使用左側列表閱讀。'; }
}
$$('[data-search-open]').forEach(button => button.addEventListener('click', openSearch));
document.addEventListener('keydown', event => { if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k' && !$('.image-viewer').open) { event.preventDefault(); if (!searchDialog.open) openSearch(); else searchInput.focus(); } });
let queryVersion = 0;
async function search() {
  const version = ++queryVersion;
  const query = searchInput.value.trim().toLocaleLowerCase();
  const results = $('#search-results');
  if (!query) { results.innerHTML = '<p class="empty-state">輸入關鍵字搜尋章節。</p>'; return; }
  try {
    const index = await searchIndex;
    if (version !== queryVersion) return;
    const terms = query.split(/\s+/);
    const matches = index.map(item => ({ ...item, score: terms.every(term => `${item.title} ${item.content} ${item.page}`.toLocaleLowerCase().includes(term)) ? terms.reduce((n, term) => n + (item.title.toLocaleLowerCase().includes(term) ? 5 : 1), 0) : 0 })).filter(item => item.score).sort((a, b) => b.score - a.score).slice(0, 24);
    results.replaceChildren();
    if (!matches.length) { results.innerHTML = '<p class="empty-state">沒有找到相符內容，試試其他關鍵字。</p>'; return; }
    for (const item of matches) {
      const a = document.createElement('a'); a.className = 'search-result'; a.href = item.url;
      const small = document.createElement('small'); small.textContent = item.page;
      const title = document.createElement('strong'); title.textContent = item.title;
      const excerpt = document.createElement('p');
      const offset = Math.max(0, item.content.toLocaleLowerCase().indexOf(terms[0]) - 35);
      excerpt.textContent = `${offset ? '…' : ''}${item.content.slice(offset, offset + 145)}${item.content.length > offset + 145 ? '…' : ''}`;
      a.append(small, title, excerpt); a.addEventListener('click', () => searchDialog.close()); results.append(a);
    }
  } catch { results.textContent = '搜尋索引暫時無法載入，請使用左側列表閱讀。'; }
}
searchInput.addEventListener('input', search);
// Expand a directly linked API definition before the browser scrolls to it.
function revealAnchor() {
  let id; try { id = decodeURIComponent(location.hash.slice(1)); } catch { return; }
  const target = document.getElementById(id);
  if (!target) return;
  let parent = target;
  while (parent) { if (parent.tagName === 'DETAILS') parent.open = true; parent = parent.parentElement; }
  requestAnimationFrame(() => target.scrollIntoView());
}
addEventListener('hashchange', revealAnchor);
if (location.hash) revealAnchor();
const viewer = $('.image-viewer');
const showViewer = modal(viewer, '[data-viewer-close]');
const stage = $('.viewer-stage');
const viewerImage = $('.viewer-image');
let zoom = 1, fit = 1, x = 0, y = 0, loadVersion = 0;
let ownedURL;
function draw() { viewerImage.style.transform = `translate(-50%, -50%) translate(${x}px, ${y}px) scale(${fit * zoom})`; $('#zoom-level').value = `${Math.round(zoom * 100)}%`; }
function resetView() {
  if (!viewerImage.naturalWidth) return;
  fit = Math.min(Math.max(10, stage.clientWidth - 36) / viewerImage.naturalWidth, Math.max(10, stage.clientHeight - 36) / viewerImage.naturalHeight, 1);
  zoom = 1; x = 0; y = 0; draw();
}
function scaleTo(value, px = 0, py = 0) { const next = Math.max(.5, Math.min(8, value)); const ratio = next / zoom; x = px - (px - x) * ratio; y = py - (py - y) * ratio; zoom = next; draw(); }
async function openImage(url, title, own = false) {
  const version = ++loadVersion;
  if (ownedURL) URL.revokeObjectURL(ownedURL);
  ownedURL = own ? url : undefined;
  $('#viewer-title').textContent = title || '圖表檢視';
  viewerImage.alt = title || '流程圖'; viewerImage.style.visibility = 'hidden';
  $('#viewer-download').href = url; $('#viewer-download').download = own ? 'archone-diagram.svg' : url.split('/').at(-1);
  if (!viewer.open) showViewer();
  viewerImage.src = url;
  try { await viewerImage.decode(); if (version !== loadVersion || !viewer.open) return; viewerImage.style.width = `${viewerImage.naturalWidth}px`; viewerImage.style.height = `${viewerImage.naturalHeight}px`; resetView(); viewerImage.style.visibility = 'visible'; stage.focus(); }
  catch { if (version === loadVersion) { viewer.close(); toast('無法載入圖檔，請重新開啟。'); } }
}
viewer.addEventListener('close', () => { loadVersion++; pointers.clear(); if (ownedURL) URL.revokeObjectURL(ownedURL); ownedURL = undefined; viewerImage.removeAttribute('src'); });
$('[data-zoom-in]').addEventListener('click', () => scaleTo(zoom * 1.4));
$('[data-zoom-out]').addEventListener('click', () => scaleTo(zoom / 1.4));
$('[data-zoom-reset]').addEventListener('click', resetView);
addEventListener('resize', () => { if (viewer.open) resetView(); });
stage.addEventListener('wheel', event => { event.preventDefault(); const r = stage.getBoundingClientRect(); scaleTo(zoom * Math.exp(-event.deltaY * .002), event.clientX - r.left - r.width / 2, event.clientY - r.top - r.height / 2); }, { passive: false });
stage.addEventListener('dblclick', () => scaleTo(zoom > 1.5 ? 1 : 2));
stage.addEventListener('keydown', event => {
  if (['+', '=', '-', '0', 'ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) event.preventDefault();
  if (event.key === '+' || event.key === '=') scaleTo(zoom * 1.4);
  if (event.key === '-') scaleTo(zoom / 1.4);
  if (event.key === '0') resetView();
  if (event.key === 'ArrowLeft') x += 40;
  if (event.key === 'ArrowRight') x -= 40;
  if (event.key === 'ArrowUp') y += 40;
  if (event.key === 'ArrowDown') y -= 40;
  draw();
});
const pointers = new Map();
const point = event => ({ x: event.clientX, y: event.clientY });
function gesture() {
  const values = [...pointers.values()];
  if (values.length < 2) return { ...values[0], distance: 0 };
  return { x: (values[0].x + values[1].x) / 2, y: (values[0].y + values[1].y) / 2, distance: Math.hypot(values[0].x - values[1].x, values[0].y - values[1].y) };
}
stage.addEventListener('pointerdown', event => { if (event.button !== 0 && event.pointerType === 'mouse') return; stage.setPointerCapture(event.pointerId); pointers.set(event.pointerId, point(event)); });
stage.addEventListener('pointermove', event => {
  if (!pointers.has(event.pointerId)) return;
  const before = gesture(); pointers.set(event.pointerId, point(event)); const after = gesture();
  const r = stage.getBoundingClientRect();
  if (before.distance && after.distance) scaleTo(zoom * after.distance / before.distance, before.x - r.left - r.width / 2, before.y - r.top - r.height / 2);
  x += after.x - before.x; y += after.y - before.y; draw();
});
for (const type of ['pointerup', 'pointercancel', 'lostpointercapture']) stage.addEventListener(type, event => pointers.delete(event.pointerId));
document.addEventListener('click', event => {
  const image = event.target.closest('[data-image], [data-image-link], [data-zoom-image]');
  if (image) { event.preventDefault(); openImage(image.dataset.image || image.getAttribute('href') || image.src, image.dataset.title || image.alt || image.textContent); return; }
  const figure = event.target.closest('.diagram');
  if (figure && (event.target.closest('[data-diagram-open]') || event.target.closest('.diagram-preview svg'))) {
    const svg = $('svg', $('.diagram-preview', figure)); if (!svg) return;
    const copy = svg.cloneNode(true); copy.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
    const vb = svg.viewBox.baseVal; copy.setAttribute('width', String(vb.width)); copy.setAttribute('height', String(vb.height)); copy.style.maxWidth = 'none'; copy.style.width = `${vb.width}px`; copy.style.height = `${vb.height}px`;
    const url = URL.createObjectURL(new Blob([new XMLSerializer().serializeToString(copy)], { type: 'image/svg+xml' }));
    const headings = $$('h2,h3', $('.prose')); const heading = headings.filter(el => el.compareDocumentPosition(figure) & Node.DOCUMENT_POSITION_FOLLOWING).at(-1);
    openImage(url, heading?.textContent || '流程與架構圖', true);
  }
});
document.addEventListener('keydown', event => { if (event.target.matches('[data-zoom-image]') && ['Enter', ' '].includes(event.key)) { event.preventDefault(); event.target.click(); } });
let renderer;
const observer = new IntersectionObserver(entries => { for (const entry of entries) if (entry.isIntersecting) { observer.unobserve(entry.target); renderer ??= import('./diagrams.js'); renderer.then(module => module.renderDiagram(entry.target)).catch(error => { entry.target.dataset.rendered = 'error'; console.error(error); }); } }, { rootMargin: '250px' });
$$('.diagram').forEach(figure => observer.observe(figure));
