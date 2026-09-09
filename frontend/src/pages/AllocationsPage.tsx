import { useOwnerSession } from '../owner/OwnerSession';
import { Select, SelectOption } from '../components/Select';
import { useEffect, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import type { Catalog } from '../api/catalog';
import { listConfirmedStockOperations } from '../api/client';
import type { StockOperationView } from '../api/types';
import { FulfillmentDrawerRoute } from '../components/FulfillmentDrawer';
import { detailUrl, isUuid, parseDetail, receiptUrl } from '../fulfillment/navigation';
import { useCatalog } from '../hooks/useCatalog';
import styles from './AllocationsPage.module.css';

const LIMIT = 200;
const time = (value: string | null) => value ? new Date(value).toLocaleString('zh-TW') : '—';

function resolveLocation(catalog: Catalog, row: StockOperationView) {
  for (const facility of catalog.facilitiesOf(row.operation.ownerId)) {
    const location = catalog.locationsOf(facility.facilityId)
      .find(candidate => candidate.locationId === row.operation.fromLocationId);
    if (location) return { facility, location };
  }
  return null;
}

export function AllocationsPage() {
  const selectedOwnerId = useOwnerSession()?.owner?.ownerId;
  const catalog = useCatalog();
  const location = useLocation();
  const navigate = useNavigate();
  const { list } = parseDetail(location.pathname, location.search);
  const { filters } = list;
  const [rows, setRows] = useState<StockOperationView[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    void listConfirmedStockOperations(LIMIT, controller.signal, selectedOwnerId).then(data => {
      if (!controller.signal.aborted) setRows(selectedOwnerId ? data.filter(row => row.operation.ownerId === selectedOwnerId) : data);
    }).catch((cause: unknown) => {
      if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : String(cause));
    }).finally(() => {
      if (!controller.signal.aborted) setLoading(false);
    });
    return () => controller.abort();
  }, [revision, selectedOwnerId]);

  const owners = new Map((rows ?? []).map(row => [row.operation.ownerId,
    catalog.owners.find(owner => owner.ownerId === row.operation.ownerId)?.name ?? row.operation.ownerId]));
  const facilities = new Map((rows ?? []).flatMap(row => {
    const resolved = resolveLocation(catalog, row);
    return resolved ? [[resolved.facility.facilityId, resolved.facility.name] as const] : [];
  }));
  if (filters.ownerId && !owners.has(filters.ownerId)) owners.set(filters.ownerId, filters.ownerId);
  if (filters.facilityId && !facilities.has(filters.facilityId)) facilities.set(filters.facilityId, filters.facilityId);
  const sources = [...new Set([...(rows ?? []).map(row => row.source.type), ...(filters.source ? [filters.source] : [])])];
  const filtered = (rows ?? []).filter(row => (!selectedOwnerId || row.operation.ownerId === selectedOwnerId)
    && (!filters.source || row.source.type === filters.source)
    && (!filters.facilityId || resolveLocation(catalog, row)?.facility.facilityId === filters.facilityId)
    && (!filters.sku || row.moves.some(move => move.skuCode.toLowerCase().includes(filters.sku!.toLowerCase()))));

  function changeFilter(key: keyof typeof filters, value: string) {
    const params = new URLSearchParams(location.search);
    if (value) params.set(key, value);
    else params.delete(key);
    void navigate({ pathname: '/allocations', search: params.toString() }, { replace: true });
  }

  return <div className={styles.page}>
    <header className={styles.heading}>
      <h2 tabIndex={-1} data-fulfillment-list-heading>配貨佇列</h2>
      <button disabled={loading} onClick={() => setRevision(value => value + 1)}>
        {loading ? '載入中…' : '重新整理佇列'}
      </button>
    </header>
    <p>等待分配的出庫作業（CONFIRMED）。等待可能來自庫存、批次條件或前序需求，並不一定是缺貨。</p>
    <p>最多載入 {LIMIT} 筆；篩選僅套用已載入資料，不代表全量搜尋或全域配貨順位。</p>
    <div className={styles.filters}>
      <label>設施<Select value={filters.facilityId ?? ''} onValueChange={value => changeFilter('facilityId', value)}>
        <SelectOption value="">全部設施</SelectOption>
        {[...facilities].map(([id, name]) => <SelectOption key={id} value={id}>{name}</SelectOption>)}
      </Select></label>
      <label>來源<Select value={filters.source ?? ''} onValueChange={value => changeFilter('source', value)}>
        <SelectOption value="">全部來源</SelectOption>
        {sources.map(source => <SelectOption key={source} value={source}>{source}</SelectOption>)}
      </Select></label>
      <label>SKU<input value={filters.sku ?? ''} onChange={event => changeFilter('sku', event.target.value)} placeholder="輸入 SKU 關鍵字" /></label>
      <button onClick={() => {
        const params = new URLSearchParams(location.search);
        for (const key of ['ownerId', 'facilityId', 'source', 'sku']) params.delete(key);
        void navigate({ pathname: '/allocations', search: params.toString() }, { replace: true });
      }}>清除篩選</button>
    </div>
    {error ? <p role="alert" className={styles.error}>佇列讀取失敗：{error}{rows ? '。下方保留上次資料，可能已過時。' : ''}</p> : null}
    {rows ? <>
      <p role="status">已載入 {rows.length} 筆，符合篩選 {filtered.length} 筆。</p>
      {rows.length >= LIMIT ? <p className={styles.warning}>已達 200 筆上限，可能仍有未載入的作業；篩選結果也只涵蓋這 200 筆。</p> : null}
      {rows.length === 0 ? <p>目前沒有等待分配的作業。</p>
        : filtered.length === 0 ? <p>已載入的作業中沒有符合篩選的資料。</p>
          : <div className={styles.cards}>{filtered.map(row => {
            const operation = row.operation;
            const resolved = resolveLocation(catalog, row);
            const orderId = row.source.type === 'ORDER' && row.source.operationUnitKey === 'PRIMARY'
              && isUuid(row.source.sourceId) ? row.source.sourceId : null;
            return <article className={styles.card} key={operation.stockOperationId}>
              <div className={styles.heading}>
                <h3>{row.source.type} · {row.source.operationUnitKey || '—'}</h3>
                {orderId ? <button onClick={() => void navigate(detailUrl('/allocations', location.search, orderId))}>查看履約</button>
                  : <span>此來源無可用的訂單履約入口</span>}
              </div>
              <dl className={styles.facts}>
                <dt>貨主</dt><dd>{owners.get(operation.ownerId)}</dd>
                <dt>設施</dt><dd>{resolved?.facility.name ?? '尚未解析'}</dd>
                <dt>來源庫位</dt><dd>{resolved ? resolved.location.name : operation.fromLocationId ?? '尚未提供'}
                  {!resolved ? '（主檔尚未解析）' : ''}</dd>
                <dt>入列時間</dt><dd>{time(operation.enqueuedAt)}</dd>
                <dt>最晚離倉時間</dt><dd>{time(operation.dispatchBy)}</dd>
                <dt>釋放優先級</dt><dd>{operation.releasePriority ?? '—'}</dd>
                <dt>作業 ID</dt><dd>{operation.stockOperationId}</dd>
                <dt>來源 ID</dt><dd>{row.source.sourceId || '—'}</dd>
              </dl>
              <details><summary>SKU 需求明細（{row.moves.length} 行）</summary>
                {row.moves.length === 0 ? <p>尚無需求明細。</p> : <ul className={styles.moves}>{row.moves.map(move => <li key={move.moveId}>
                  <div>第 {move.lineSequence ?? '—'} 行 · <strong>{move.skuCode}</strong> × {move.quantity} · {move.state ?? '未知'}</div>
                  <small>Move ID：{move.moveId}</small>
                  {orderId && resolved && operation.direction === 'OUTBOUND'
                    && [operation.ownerId, resolved.facility.facilityId, resolved.location.locationId].every(isUuid)
                    && catalog.findSku(operation.ownerId, move.skuCode)
                    ? <Link to={receiptUrl({ ownerId: operation.ownerId, facilityId: resolved.facility.facilityId,
                      locationId: resolved.location.locationId, sku: move.skuCode, returnOrderId: orderId, list })}>前往庫存補貨</Link>
                    : <small>目前無法提供定位補貨入口。</small>}
                </li>)}</ul>}
                <p>數量為原始需求，不是精確缺口。前往庫存後請核對貨主、庫位及 SKU；收貨不保證由此訂單優先取得。</p>
              </details>
            </article>;
          })}</div>}
    </> : loading ? <p>正在讀取等待作業…</p> : null}
    <FulfillmentDrawerRoute catalog={catalog} />
  </div>;
}
