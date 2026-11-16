import fs from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { parseHTML } from 'linkedom';
import { parseFeature } from './features.mjs';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../dist');
const files = (await fs.readdir(root)).filter(file => file.endsWith('.html'));
const pages = new Map(await Promise.all(files.map(async file => [file, parseHTML(await fs.readFile(path.join(root, file), 'utf8')).document])));
let links = 0;
const errors = [];
for (const [file, document] of pages) {
  assert.equal(document.documentElement.lang, 'zh-Hant');
  assert.ok(document.querySelector('h1'), `${file}: missing h1`);
  const ids = [...document.querySelectorAll('[id]')].map(el => el.id);
  assert.equal(new Set(ids).size, ids.length, `${file}: duplicate IDs`);
  for (const el of document.querySelectorAll('[href], [src]')) {
    const raw = el.getAttribute('href') ?? el.getAttribute('src');
    if (/^(https?:|mailto:|data:)/.test(raw)) continue;
    const url = new URL(raw, `https://local.test/${file}`);
    const target = decodeURIComponent(url.pathname).replace(/^\//, '') || 'index.html';
    try { await fs.access(path.join(root, target)); links++; }
    catch { errors.push(`${file}: missing ${raw}`); continue; }
    if (url.hash && pages.has(target) && !pages.get(target).getElementById(decodeURIComponent(url.hash.slice(1)))) errors.push(`${file}: missing anchor ${raw}`);
  }
}
const search = JSON.parse(await fs.readFile(path.join(root, 'assets/search-index.json'), 'utf8'));
for (const item of search) {
  const [file, fragment] = item.url.split('#');
  if (!pages.has(file) || (fragment && !pages.get(file).getElementById(fragment))) errors.push(`Search: invalid route ${item.url}`);
}
assert.equal(pages.get('bpmn.html').querySelectorAll('.process-image').length, 6);
assert.equal(pages.get('api.html').querySelectorAll('[data-openapi-tag]').length, 13);
const apiSpec = JSON.parse(await fs.readFile(path.join(root, 'assets/openapi.json'), 'utf8'));
assert.equal(Object.values(apiSpec.paths).flatMap(methods => Object.keys(methods).filter(method => /^(get|post|put|patch|delete)$/.test(method))).length, 13);
assert.ok(pages.get('api.html').getElementById('swagger-ui'));
assert.ok(pages.get('api.html').querySelector('script[src="assets/swagger-ui/swagger-ui-bundle.js"]'));
const bdd = pages.get('bdd.html');
const bddText = bdd.querySelector('.prose').textContent;
const featureDir = path.resolve(root, '../../biz-bdd');
const featureFiles = (await fs.readdir(featureDir)).filter(file => file.endsWith('.feature'));
let scenarios = 0;
for (const file of featureFiles) {
  const source = await fs.readFile(path.join(featureDir, file));
  assert.deepEqual(await fs.readFile(path.join(root, 'downloads/biz-bdd', file)), source, `${file}: download differs from snapshot`);
  const feature = parseFeature(source.toString('utf8'), file);
  for (const scenario of feature.scenarios) {
    scenarios++;
    assert.ok(bdd.getElementById(scenario.id), `Missing scenario ${scenario.id}`);
    assert.ok(search.some(section => section.page === 'BDD 規格案例' && section.content.includes(scenario.id)), `Missing searchable case ${scenario.id}`);
  }
  for (const step of [...feature.background, ...feature.scenarios.flatMap(scenario => scenario.steps)]) {
    assert.ok(bddText.includes(step.text), `${file}: missing step ${step.text}`);
    for (const row of step.rows) for (const cell of row.split('|').slice(1, -1)) assert.ok(bddText.includes(cell.trim()));
  }
}
assert.equal(featureFiles.length, 7);
assert.equal(scenarios, 21);
assert.equal([...pages.values()].reduce((n, page) => n + page.querySelectorAll('.mermaid-source').length, 0), 17);
const dictionary = pages.get('data-dictionary.html');
let physicalFields = 0, viewFields = 0;
for (const table of dictionary.querySelectorAll('table')) {
  const headers = [...table.querySelectorAll('th')].map(el => el.textContent);
  if (!headers.includes('中文說明')) continue;
  for (const row of table.querySelectorAll('tbody tr')) {
    const cells = [...row.querySelectorAll('td')];
    assert.equal(cells.length, headers.length, 'Dictionary table must have consistent columns');
    assert.match(cells[1].textContent, /[\u3400-\u9fff]/, 'Each field needs a Chinese explanation');
    if (headers[0] === 'View 欄位') viewFields++; else physicalFields++;
  }
}
assert.equal(physicalFields, 209);
assert.equal(viewFields, 13);
const storage = pages.get('data-model.html');
const indexTable = [...storage.querySelectorAll('table')].find(el => el.querySelector('th')?.textContent === '索引定義');
assert.equal(indexTable.querySelectorAll('tbody tr').length, 25, 'All index definitions must stay inside the table');
assert.ok(files.every(file => !file.startsWith('source-')), 'No implementation source pages');
for (const page of pages.values()) {
  const text = page.querySelector('main').textContent;
  assert.ok(!text.includes('原始碼依據') && !text.includes('WMS 取消判斷'));
  for (const heading of page.querySelectorAll('.prose h2')) assert.match(heading.textContent, /^\d+ /);
  for (const heading of page.querySelectorAll('.prose h3')) assert.match(heading.textContent, /^\d+-\d+ /);
}
assert.equal(errors.length, 0, errors.join('\n'));
console.log(`Checked ${pages.size} HTML pages, ${links} local links, ${search.length} search sections, 7 features / 21 scenarios, 17 Mermaid diagrams, 6 BPMN models, 25 indexes and 222 Chinese field descriptions.`);
