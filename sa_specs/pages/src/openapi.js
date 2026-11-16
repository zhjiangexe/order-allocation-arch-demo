// Preserve existing search/bookmark URLs while using Swagger UI's native deep links.
function normalizeOperationHash() {
  let id;
  try { id = decodeURIComponent(location.hash.slice(1)); } catch { return; }
  const anchor = document.getElementById(id);
  if (!anchor?.hasAttribute('data-openapi-tag')) return;
  const hash = `#/${encodeURIComponent(anchor.dataset.openapiTag)}/${encodeURIComponent(id)}`;
  history.replaceState(null, '', `${location.pathname}${location.search}${hash}`);
}

let swaggerUI;
function revealOperationHash() {
  normalizeOperationHash();
  if (!swaggerUI || !location.hash.startsWith('#/')) return;
  try { swaggerUI.layoutActions.parseDeepLinkHash(decodeURIComponent(location.hash)); } catch { /* Ignore malformed bookmarks. */ }
}
normalizeOperationHash();
addEventListener('hashchange', revealOperationHash);

async function renderOpenAPI() {
  const status = document.querySelector('.api-load-status');
  try {
    const response = await fetch('assets/openapi.json');
    if (!response.ok) throw new Error('OpenAPI definition unavailable');
    const spec = await response.json();
    swaggerUI = window.SwaggerUIBundle({
      dom_id: '#swagger-ui',
      spec,
      deepLinking: true,
      docExpansion: 'list',
      defaultModelsExpandDepth: 1,
      defaultModelExpandDepth: 2,
      filter: true,
      displayRequestDuration: false,
      supportedSubmitMethods: [],
      validatorUrl: null,
      persistAuthorization: false,
      plugins: [() => ({ components: { InfoContainer: () => null, ServersContainer: () => null } })],
      onComplete() {
        status.hidden = true;
        document.getElementById('swagger-ui').dataset.ready = 'true';
        queueMicrotask(revealOperationHash);
      },
    });
  } catch {
    status.textContent = 'API 文件載入失敗，請重新整理，或下載上方的 openapi.yaml。';
    status.setAttribute('role', 'alert');
  }
}

renderOpenAPI();
