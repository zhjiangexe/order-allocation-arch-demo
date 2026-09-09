/** 對應後端 OrderFulfillmentWorkflow.WORKFLOW_ID_PREFIX；不指定 runId，以開啟該 Workflow。 */
export function temporalWorkflowUrl(orderId: string): string | null {
  const base = import.meta.env.VITE_TEMPORAL_UI_URL ?? 'http://localhost:28296';
  const namespace = import.meta.env.VITE_TEMPORAL_NAMESPACE ?? 'default';
  if (!base.trim() || !namespace.trim()) return null;
  try {
    const url = new URL(base);
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return null;
    url.pathname = `${url.pathname.replace(/\/$/, '')}/namespaces/${encodeURIComponent(namespace)}/workflows/${encodeURIComponent(`order-fulfillment/${orderId}`)}`;
    url.search = '';
    url.hash = '';
    return url.toString();
  } catch {
    return null;
  }
}
