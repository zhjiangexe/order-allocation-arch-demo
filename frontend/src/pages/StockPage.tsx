import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useBlocker, useLocation } from 'react-router';

import type { Catalog } from '../api/catalog';
import { getStockInLocation, confirmStockReceipt } from '../api/client';
import { facilityStockLines, type StockLine } from '../api/stockLines';
import type { ConfirmStockReceiptRequest, StockReceiptConfirmed } from '../api/types';
import { StockPanel } from '../components/StockPanel';
import type { ConfirmStockReceiptInput } from '../components/ConfirmStockReceiptDialog';
import { detailUrl, parseReceiptContext, returnToFulfillment, validateReceiptContext,
  type ReceiptContext } from '../fulfillment/navigation';
import type { AsyncState } from '../hooks/useAsyncAction';
import { useCatalogState } from '../hooks/useCatalog';
import styles from './OrdersPage.module.css';

export function StockPage() {
  const { search } = useLocation();
  const { catalog, loading, error } = useCatalogState();
  const context = parseReceiptContext(search);
  const validation = context === null ? 'invalid' : validateReceiptContext(context, {
    ownerId: context.ownerId,
    owners: loading ? null : catalog.owners,
    facilities: [...catalog.facilitiesOf(context.ownerId)],
    locations: [...catalog.locationsOf(context.facilityId)],
    skus: catalog.skuCodesOf(context.ownerId).flatMap(code => {
      const sku = catalog.findSku(context.ownerId, code);
      return sku ? [sku] : [];
    }),
  });

  return <div className={styles.page}><section className={styles.section}>
    <h2 className={styles.sectionHeading}>庫存與收貨確認</h2>
    {loading ? <p role="status">載入主檔中…</p> : error ? <p role="alert">主檔載入失敗：{error}，請重新載入頁面。</p> : <>
      {search && validation !== 'valid' ? <p role="alert">補貨定位參數無效或不屬於指定貨主／設施，請手動選擇貨主、設施與庫位。</p> : null}
      <StockSession key={search} catalog={catalog} context={validation === 'valid' ? context : null} />
    </>}
  </section></div>;
}

function StockSession({ catalog, context }: { catalog: Catalog; context: ReceiptContext | null }) {
  const [lines, setLines] = useState<AsyncState<StockLine[]>>({ status: 'idle' });
  const [receipt, setReceipt] = useState<AsyncState<StockReceiptConfirmed>>({ status: 'idle' });
  const command = useRef<ConfirmStockReceiptRequest | null>(null);
  const inFlight = useRef(false);
  const [unconfirmed, setUnconfirmed] = useState(false);
  const [startingAnother, setStartingAnother] = useState(false);
  const queryVersion = useRef(0);
  const mounted = useRef(true);
  const blocker = useBlocker(unconfirmed);

  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; queryVersion.current++; };
  }, []);

  useEffect(() => {
    if (!unconfirmed) return;
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ''; };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [unconfirmed]);

  const query = useCallback(async (ownerId: string, locationId: string) => {
    const version = ++queryVersion.current;
    setLines({ status: 'pending' });
    try {
      const result = await getStockInLocation(ownerId, locationId);
      if (mounted.current && version === queryVersion.current)
        setLines({ status: 'success', data: facilityStockLines(catalog, ownerId, result) });
    } catch (error) {
      if (mounted.current && version === queryVersion.current)
        setLines({ status: 'failure', message: error instanceof Error ? error.message : String(error) });
    }
  }, [catalog]);

  const ownerId = context?.ownerId;
  const locationId = context?.locationId;
  useEffect(() => {
    if (ownerId && locationId) void query(ownerId, locationId);
  }, [ownerId, locationId, query]);

  async function send(input?: ConfirmStockReceiptInput) {
    if (inFlight.current || (input && command.current)) return;
    if (input) command.current = { ...input, receiptId: crypto.randomUUID() };
    if (!command.current) return;
    inFlight.current = true;
    setUnconfirmed(true);
    setReceipt({ status: 'pending' });
    try {
      const result = await confirmStockReceipt(command.current);
      if (!mounted.current) return;
      setReceipt({ status: 'success', data: result });
      setUnconfirmed(false);
    } catch (error) {
      if (mounted.current) setReceipt({ status: 'failure', message: error instanceof Error ? error.message : String(error) });
    } finally {
      inFlight.current = false;
    }
  }

  function resetReceipt() {
    if (inFlight.current) return;
    command.current = null;
    setUnconfirmed(false);
    setStartingAnother(false);
    setReceipt({ status: 'idle' });
  }

  return <>
    {context ? <p>
      補貨目標：{context.sku}。請確認實收數量後送出。{' '}
      <Link to={returnToFulfillment(context)}>返回履約</Link>{' · '}
      <Link to={detailUrl(context.list.page, new URLSearchParams(context.list.filters).toString(), null)}>
        {context.list.page === '/allocations' ? '返回原佇列' : '返回訂單列表'}
      </Link>
    </p> : null}
    {command.current ? <div>
      <p>收貨識別碼：<code>{command.current.receiptId}</code>；SKU：{command.current.sku}；
        數量：{command.current.quantity}；收貨日：{command.current.inDate}；效期：{command.current.expiryDate}</p>
      {receipt.status === 'failure' ? <div role="alert">
        <p>本次收貨尚未確認成功。重試會使用相同識別碼與原始內容。重新載入或離開頁面後無法恢復此請求。</p>
        <button type="button" onClick={() => void send()}>重試原收貨</button>{' '}
        <button type="button" onClick={() => setStartingAnother(true)}>另起收貨操作</button>
      </div> : null}
      {receipt.status === 'success' ? <button type="button" onClick={resetReceipt}>開始另一筆收貨</button> : null}
      {startingAnother ? <div role="alert">
        <p>原收貨可能已入帳，另起操作會使用新的識別碼，可能重複增加庫存。請先確認原收貨結果。</p>
        <button type="button" onClick={resetReceipt}>已確認，另起操作</button>{' '}
        <button type="button" onClick={() => setStartingAnother(false)}>保留原收貨</button>
      </div> : null}
    </div> : null}
    {blocker.state === 'blocked' ? <div role="alert">
      <p>收貨結果尚未確認。離開後將失去原收貨的重試資料，重新載入也無法恢復。</p>
      <button type="button" onClick={() => blocker.reset()}>留在此頁</button>{' '}
      <button type="button" onClick={() => blocker.proceed()}>仍要離開</button>
    </div> : null}
    <StockPanel
      initialScope={context ?? undefined}
      receiptLocked={receipt.status !== 'idle'}
      lines={lines}
      receiptConfirmation={receipt}
      owners={catalog.owners}
      facilitiesOf={owner => catalog.facilitiesOf(owner)}
      locationsOf={facility => catalog.locationsOf(facility)}
      onQuery={(owner, _facility, location) => void query(owner, location)}
      onConfirmReceipt={input => void send(input)}
      onScopeChange={() => { queryVersion.current++; setLines({ status: 'idle' }); }}
    />
  </>;
}
