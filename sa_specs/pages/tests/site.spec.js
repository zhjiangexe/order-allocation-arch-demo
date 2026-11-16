import { test, expect } from '@playwright/test';
const docs = ['business', 'integration', 'data-model', 'data-dictionary', 'bdd', 'verification', 'runbook', 'process-spec', 'bpmn-guide'];
test('all documents fit the viewport and diagrams render', async ({ page }, testInfo) => {
  const failures = [];
  page.on('pageerror', e => failures.push(e.message));
  page.on('response', r => { if (r.status() >= 400) failures.push(`${r.status()} ${r.url()}`); });
  for (const route of ['index', ...docs, 'bpmn', 'api']) {
    await page.goto(`/${route}.html`);
    await expect(page.locator('h1')).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), route).toBeTruthy();
    const diagrams = page.locator('.diagram');
    for (let i = 0; i < await diagrams.count(); i++) {
      await diagrams.nth(i).scrollIntoViewIfNeeded();
      await expect(diagrams.nth(i)).toHaveAttribute('data-rendered', 'true', { timeout: 30000 });
      await expect(diagrams.nth(i).locator('[data-diagram-open]')).toBeEnabled();
    }
    if (['index', 'data-dictionary', 'bpmn', 'bdd'].includes(route)) {
      await page.evaluate(() => scrollTo(0, 0));
      await page.screenshot({ path: `test-results/${testInfo.project.name}-${route}.png`, fullPage: route === 'index' });
    }
  }
  expect(failures).toEqual([]);
});
test('navigation, search and deep links', async ({ page }, testInfo) => {
  await page.goto('/index.html');
  if (testInfo.project.name === 'mobile') {
    await page.getByRole('button', { name: '開啟導覽', exact: true }).click();
    await expect(page.locator('.sidebar')).toBeVisible();
    await expect(page.locator('.workspace')).toHaveAttribute('inert', '');
  }
  await page.locator('.nav-link[href="data-dictionary.html"]').click();
  await expect(page).toHaveURL(/data-dictionary/);
  await page.locator('[data-search-open]').click();
  await page.getByRole('searchbox').fill('stock_pools');
  await expect(page.locator('.search-result').first()).toBeVisible();
  await page.locator('.search-result').first().click();
  await expect(page.locator('.search-dialog')).not.toBeVisible();
  await expect(page).toHaveURL(/#/);
  await page.reload();
  await expect(page.locator('h1')).toBeVisible();
  await page.goto('/api.html');
  const id = await page.locator('[data-openapi-tag]').first().getAttribute('id');
  await page.goto(`/api.html#${id}`);
  await expect(page.locator('#operations-Ordering-placeOrder')).toHaveClass(/is-open/);
});
test('image viewer zoom, pan, reset, close and focus', async ({ page }, testInfo) => {
  await page.goto('/bpmn.html');
  const button = page.locator('.process-image').first();
  await button.click();
  await expect(page.locator('.image-viewer')).toBeVisible();
  await expect(page.locator('.viewer-image')).toBeVisible();
  await expect(page.locator('.viewer-stage')).toBeFocused();
  await page.getByRole('button', { name: '放大', exact: true }).click();
  await expect(page.locator('#zoom-level')).toHaveText('140%');
  const box = await page.locator('.viewer-stage').boundingBox();
  const transform = await page.locator('.viewer-image').getAttribute('style');
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down(); await page.mouse.move(box.x + box.width / 2 + 50, box.y + box.height / 2 + 20); await page.mouse.up();
  expect(await page.locator('.viewer-image').getAttribute('style')).not.toEqual(transform);
  await page.getByRole('button', { name: '適合畫面' }).click();
  await expect(page.locator('#zoom-level')).toHaveText('100%');
  if (testInfo.project.name === 'mobile') {
    const cdp = await page.context().newCDPSession(page);
    const center = { x: box.x + box.width / 2, y: box.y + box.height / 2 };
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: center.x - 30, y: center.y, id: 1 }, { x: center.x + 30, y: center.y, id: 2 }] });
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: center.x - 80, y: center.y, id: 1 }, { x: center.x + 80, y: center.y, id: 2 }] });
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    await expect(page.locator('#zoom-level')).not.toHaveText('100%');
  }
  await page.screenshot({ path: `test-results/${testInfo.project.name}-viewer.png` });
  await page.getByRole('button', { name: '關閉圖片' }).click();
  await expect(page.locator('.image-viewer')).not.toBeVisible();
  await expect(button).toBeFocused();
  await button.click(); await expect(page.locator('.viewer-image')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.locator('.image-viewer')).not.toBeVisible();
});
test('Mermaid opens as a scalable image', async ({ page }) => {
  await page.goto('/business.html');
  const diagram = page.locator('.diagram').first();
  await diagram.scrollIntoViewIfNeeded();
  await expect(diagram).toHaveAttribute('data-rendered', 'true');
  await diagram.locator('[data-diagram-open]').click();
  await expect(page.locator('.viewer-image')).toBeVisible();
  expect(await page.locator('.viewer-image').evaluate(image => image.naturalWidth)).toBeGreaterThan(100);
  await page.keyboard.press('Escape');
  await expect(diagram.locator('[data-diagram-open]')).toBeFocused();
});
test('narrow mobile width and missing route', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 720 });
  for (const route of ['index', 'data-dictionary', 'api', 'bpmn', 'bdd']) {
    await page.goto(`/${route}.html`);
    if (route === 'api') await expect(page.locator('#swagger-ui')).toHaveAttribute('data-ready', 'true');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), route).toBeTruthy();
  }
  const response = await page.goto('/missing/deep/page');
  expect(response.status()).toBe(404);
  await expect(page.locator('h1')).toHaveText('找不到這個頁面');
  await expect(page.locator('.sidebar')).toHaveCSS('position', 'fixed');
});

test('Bootstrap document presentation, numbering and dictionary explanations', async ({ page }) => {
  await page.goto('/index.html');
  await expect(page.locator('body')).toHaveCSS('background-color', 'rgb(241, 243, 245)');
  await expect(page.locator('.main-content')).toHaveCSS('background-color', 'rgb(255, 255, 255)');
  await expect(page.locator('link[href="assets/bootstrap.min.css"]')).toHaveCount(1);
  await expect(page.locator('.hero, .facts, .decision-grid')).toHaveCount(0);
  await expect(page.locator('.prose')).toHaveCSS('font-size', '17px');
  const navigation = await page.locator('.nav-link').allTextContents();
  expect(navigation.slice(1)).toEqual(['01業務流程與範疇', '02BPMN 流程圖集', '03資料模型與儲存', '04資料字典', '05OPENAPI', '06整合與交易時序', '07BDD 規格案例', '08UAT 與驗證', '09故障排除手冊']);
  await page.goto('/bpmn.html');
  await expect(page.locator('.process-image')).toHaveCount(6);
  await expect(page.locator('.prose h3').first()).toHaveText('1-1 訂單履約總覽');
  await expect(page.locator('.prose')).not.toContainText('WMS 取消判斷');
  await page.goto('/data-dictionary.html#owners');
  await expect(page.locator('.prose h3').first()).toHaveText('1-1 貨主 owners');
  const fields = page.locator('.table-scroll table').nth(1);
  await expect(fields).toContainText('中文說明');
  await expect(fields).toContainText('貨主的唯一識別碼');
  await expect(fields).toHaveCSS('font-size', '15px');
  await page.goto('/api.html');
  await expect(page.locator('.api-description')).toContainText('未使用 PUT、PATCH 或 DELETE');
});

test('BDD scenarios support search, deep links and exact feature downloads', async ({ page }) => {
  await page.goto('/bdd.html');
  await expect(page.locator('.prose h3')).toHaveCount(21);
  await expect(page.locator('.gherkin-keyword').first()).toBeVisible();
  const download = page.locator('.prose a[download]').first();
  const response = await page.request.get(await download.getAttribute('href'));
  expect(response.ok()).toBeTruthy();
  expect(await response.text()).toContain('# case-id: CAT-001');
  await page.locator('[data-search-open]').click();
  await page.getByRole('searchbox').fill('EVT-FUL-003');
  const result = page.locator('.search-result').filter({ hasText: 'BDD 規格案例' });
  await expect(result).toHaveCount(1);
  await result.click();
  await expect(page).toHaveURL(/bdd.*#/);
  await page.reload();
  await expect(page.locator('.prose')).toContainText('ship-complete');
  await page.goto('/bdd.html#EVT-FUL-003');
  await expect(page.locator('h3').filter({ hasText: 'ship-complete' })).toBeInViewport();
  await page.goto('/api.html');
  await expect(page.locator('.page-pagination a').first()).toHaveAttribute('href', 'data-dictionary.html');
  await expect(page.locator('.page-pagination a').last()).toHaveAttribute('href', 'integration.html');
});

test('swimlane infrastructure renders below business services', async ({ page }, testInfo) => {
  await page.goto('/business.html');
  const diagram = page.locator('.diagram').nth(1);
  await diagram.scrollIntoViewIfNeeded();
  await expect(diagram).toHaveAttribute('data-rendered', 'true');
  const bounds = await diagram.locator('.cluster').evaluateAll(clusters => {
    const get = name => {
      const cluster = clusters.find(node => node.textContent.includes(name));
      if (!cluster) return null;
      const rect = cluster.getBoundingClientRect();
      return { top: rect.top, bottom: rect.bottom };
    };
    return { services: get('核心業務服務'), infrastructure: get('資料與事件基礎設施'), people: get('上游與作業人員') };
  });
  expect(bounds.infrastructure.top).toBeGreaterThan(bounds.services.bottom);
  expect(bounds.services.top).toBeGreaterThan(bounds.people.bottom);
  await diagram.screenshot({ path: `test-results/${testInfo.project.name}-swimlane.png` });
});

test('Swagger UI renders contracts, resolves references and keeps API links', async ({ page }, testInfo) => {
  const remoteRequests = [];
  const failures = [];
  page.on('request', request => {
    if (new URL(request.url()).origin !== 'http://127.0.0.1:4173') remoteRequests.push(request.url());
  });
  page.on('pageerror', error => failures.push(error.message));
  await page.goto('/api.html');
  await expect(page.locator('#swagger-ui')).toHaveAttribute('data-ready', 'true');
  await expect(page.locator('.opblock')).toHaveCount(13);
  await expect(page.locator('.opblock-tag')).toHaveCount(4);
  await expect(page.locator('.opblock-get').first()).toHaveCSS('border-color', 'rgb(97, 175, 254)');
  await expect(page.locator('.opblock-post').first()).toHaveCSS('border-color', 'rgb(73, 204, 144)');
  await page.screenshot({ path: `test-results/${testInfo.project.name}-openapi.png`, fullPage: true });

  // Existing global search URLs still open the corresponding Swagger operation.
  await page.locator('[data-search-open]').click();
  await page.getByRole('searchbox').fill('POST /orders');
  await page.locator('.search-result').filter({ hasText: 'POST /orders' }).first().click();
  const operation = page.locator('#operations-Ordering-placeOrder');
  await expect(operation).toHaveClass(/is-open/);
  await expect(operation).toContainText('Request body');
  await expect(operation).toContainText('externalOrderNo');
  await expect(operation).toContainText('Problem Details');
  await expect(page.locator('.try-out__btn, .execute')).toHaveCount(0);
  await page.reload();
  await expect(operation).toHaveClass(/is-open/);
  const schemas = page.locator('section.models');
  await expect(schemas).toContainText('PlaceOrderRequest');
  const schema = schemas.getByRole('article').filter({ hasText: 'PlaceOrderRequest' }).first();
  await schema.getByRole('button').first().click();
  await expect(schema).toContainText('externalOrderNo');
  await page.setViewportSize({ width: 320, height: 720 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBeTruthy();
  await operation.scrollIntoViewIfNeeded();
  await page.screenshot({ path: `test-results/${testInfo.project.name}-openapi-expanded.png` });
  expect(remoteRequests).toEqual([]);
  expect(failures).toEqual([]);
});
