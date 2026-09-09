import { useEffect, useId, useRef, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import type { Catalog } from '../api/catalog';
import type { OrderFulfillmentView } from '../api/types';
import { detailUrl, isUuid, parseDetail, receiptUrl } from '../fulfillment/navigation';
import type { ListContext } from '../fulfillment/navigation';
import { useFulfillmentTracking } from '../hooks/useFulfillmentTracking';
import styles from './FulfillmentDrawer.module.css';

const orderStatuses = [
  { value: 'PENDING', label: '待分配', description: '已收單，尚未完成庫存分配' },
  { value: 'ALLOCATED', label: '已分配', description: '庫存已分配，等待倉內作業與出貨完成' },
  { value: 'FULFILLED', label: '履約完成', description: '已交接承運商、完成出庫並記錄履約完成' },
  { value: 'CANCELLED', label: '已取消', description: '訂單已取消，非正常履約完成' },
];

const time = (value: string | number | null) => value === null ? '—' : new Date(value).toLocaleString('zh-TW');

/** 可放在 orders 或 allocations 的路由內；一次只掛一個詳情追蹤。 */
export function FulfillmentDrawerRoute({ catalog }: { catalog: Catalog }) {
  const location = useLocation();
  const navigate = useNavigate();
  const detail = parseDetail(location.pathname, location.search);
  const close = () => void navigate(detailUrl(detail.list.page, location.search, null), { replace: true });
  if (detail.invalidOrderId) return <p role="alert">訂單 ID 格式無效。<button onClick={close}>關閉詳情</button></p>;
  return detail.orderId ? <FulfillmentDrawer key={detail.orderId} orderId={detail.orderId}
    list={detail.list} catalog={catalog} onClose={close} /> : null;
}

export function FulfillmentDrawer({ orderId, list, catalog, onClose }: {
  orderId: string; list: ListContext; catalog: Catalog; onClose: () => void;
}) {
  const tracking = useFulfillmentTracking(orderId);
  const dialog = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const [copyMessage, setCopyMessage] = useState('');
  useEffect(() => {
    const previous = document.activeElement;
    const element = dialog.current!;
    element.showModal();
    const overflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      element.close();
      document.body.style.overflow = overflow;
      if (previous instanceof HTMLElement && previous !== document.body && previous.isConnected
        && !previous.matches(':disabled')) previous.focus();
      else document.querySelector<HTMLElement>('[data-fulfillment-list-heading]')?.focus();
    };
  }, []);
  async function copyIds() {
    const data = tracking.data;
    try {
      await navigator.clipboard.writeText([
        `orderId: ${orderId}`,
        ...(data?.stockOperation ? [`stockOperationId: ${data.stockOperation.operation.stockOperationId}`] : []),
        ...(data?.shipments.map(s => `shipmentId: ${s.shipmentId}`) ?? []),
      ].join('\n'));
      setCopyMessage('已複製 IDs');
    } catch { setCopyMessage('無法複製，請從下方技術資訊手動選取'); }
  }
  return <dialog ref={dialog} className={styles.drawer} aria-labelledby={titleId}
    onCancel={event => { event.preventDefault(); onClose(); }}>
    <header className={styles.header}>
      <h2 id={titleId}>訂單履約</h2>
      <button type="button" onClick={onClose}>關閉詳情</button>
    </header>
    <div className={styles.content}>
      <div className={styles.actions}>
        <button disabled={tracking.loading} onClick={tracking.refresh}>重新查詢履約</button>
        <button disabled={tracking.loading && tracking.paused}
          onClick={tracking.paused ? tracking.resume : tracking.pause}>
          {tracking.paused ? '恢復自動追蹤' : '暫停自動追蹤'}
        </button>
        <button onClick={() => void copyIds()}>複製 IDs</button>
      </div>
      <p aria-live="polite">{tracking.loading ? '查詢中…' : tracking.paused ? '自動追蹤已停止' : '自動追蹤中（每次查詢完成後間隔 2 秒）'}</p>
      <p>快照取得時間：{time(tracking.fetchedAt)}</p>
      {tracking.error ? <p role="alert" className={styles.error}>{tracking.error}。資料可能過時，請重新查詢。</p> : null}
      {copyMessage ? <p role="status">{copyMessage}</p> : null}
      {tracking.data ? <div className={styles.progress}>
        <ul className={styles.orderStatuses} aria-label="所有訂單狀態">
          {orderStatuses.map(status => <li key={status.value}>
            <span tabIndex={0} aria-describedby={`${titleId}-${status.value}-help`}
              aria-label={`${status.value}，${status.description}${status.value === tracking.data?.order.status ? '，目前狀態' : ''}`}
              className={status.value === tracking.data?.order.status ? styles.currentStatus : undefined}
              aria-current={status.value === tracking.data?.order.status ? 'step' : undefined}>
              <span>{status.label}</span>
              <small>{status.value}</small>
            </span>
            <span role="tooltip" id={`${titleId}-${status.value}-help`} className={styles.statusTooltip}>
              <strong>{status.value} · {status.label}</strong><br />{status.description}
            </span>
          </li>)}
        </ul>
        {tracking.progress && tracking.progress.state !== 'completed'
          ? <p role="status" className={styles.progressNote}>{tracking.progress.message}</p> : null}
      </div> : null}
      {tracking.data ? <FulfillmentDetails data={tracking.data} catalog={catalog} list={list} />
        : <p>{tracking.loading ? '正在讀取訂單與履約資料…' : '尚無可顯示的履約資料。'}</p>}
      <details><summary>技術資訊</summary><p>orderId：{orderId}</p>
        <p>stockOperationId：{tracking.data?.stockOperation?.operation.stockOperationId ?? '—'}</p>
        {tracking.data?.shipments.map(s => <p key={s.shipmentId}>shipmentId：{s.shipmentId}</p>)}
      </details>
    </div>
  </dialog>;
}

/** 純內容元件，fixtures 可直接驗證，不必等待輪詢。 */
export function FulfillmentDetails({ data, catalog, list }: {
  data: OrderFulfillmentView; catalog: Catalog; list: ListContext;
}) {
  const { order, stockOperation: stock, temporalWorkflow: workflow } = data;
  const operation = stock?.operation;
  const location = catalog.locationsOf(order.facilityId).find(l => l.locationId === operation?.fromLocationId);
  const canLocate = location && stock?.source.type === 'ORDER' && stock.source.sourceId === order.orderId
    && stock.source.operationUnitKey === 'PRIMARY' && operation?.ownerId === order.ownerId
    && operation.direction === 'OUTBOUND'
    && catalog.findFacility(order.ownerId, order.facilityId)
    && [order.orderId, order.ownerId, order.facilityId, location.locationId].every(isUuid);
  return <>
    <section>
      <h3>{order.externalOrderNo}</h3>
      <dl className={styles.facts}>
        <dt>模式</dt><dd>{data.orchestrationMode === 'events' ? 'Events' : data.orchestrationMode === 'temporal' ? 'Temporal' : data.orchestrationMode}</dd>
        <dt>貨主</dt><dd>{catalog.owners.find(o => o.ownerId === order.ownerId)?.name ?? order.ownerId}</dd>
        <dt>設施</dt><dd>{catalog.findFacility(order.ownerId, order.facilityId)?.name ?? order.facilityId}</dd>
        <dt>地址／分區</dt><dd>{order.shipToAddress}／{order.shipToZone}</dd>
        <dt>承諾到貨日</dt><dd>{order.promisedDeliveryDate}</dd>
        <dt>最晚離倉時間</dt><dd>{time(order.dispatchBy)}</dd>
        <dt>出庫釋放優先級</dt><dd>{order.releasePriority}</dd>
        <dt>訂單履約時間</dt><dd>{time(order.fulfilledAt)}</dd>
      </dl>
      <ul>{order.lines.map(line => <li key={line.orderLineId}>
        第 {line.lineNo} 行 · {line.skuCode} × {line.quantity}{' '}
        {canLocate && catalog.findSku(order.ownerId, line.skuCode) ? <Link to={receiptUrl({
          ownerId: order.ownerId, facilityId: order.facilityId, locationId: location.locationId,
          sku: line.skuCode, returnOrderId: order.orderId, list,
        })}>前往庫存補貨</Link> : null}
      </li>)}</ul>
      {!canLocate ? <p>尚未確認來源庫位，暫不提供定位補貨。</p>
        : <p>補貨請選擇 {location.name} 與對應 SKU；收貨不保證由本訂單優先取得。</p>}
    </section>
    <section><h3>庫存作業與分配批次</h3>
      {!stock ? <p>尚無庫存作業或分配資料。</p> : <>
        <p>作業狀態：{operation?.state ?? '未知'} · 來源：{stock.source.type}／{stock.source.operationUnitKey}</p>
        <p>入列時間：{time(operation?.enqueuedAt ?? null)} · 來源庫位：{location?.name ?? operation?.fromLocationId ?? '—'}</p>
        {stock.moves.length === 0 ? <p>尚無 Move。</p> : stock.moves.map(move => <article className={styles.card} key={move.moveId}>
          <h4>第 {move.lineSequence ?? '—'} 行 · {move.skuCode} × {move.quantity}</h4>
          <p>{move.state ?? '未知'} · 分配時間：{time(move.assignedAt)}</p>
          {move.batches.length === 0 ? <p>尚未分配批次。</p> : <div className={styles.scroll}><table>
            <thead><tr><th>批次 stockQuantId</th><th>入庫日</th><th>效期</th><th>數量</th></tr></thead>
            <tbody>{move.batches.map(batch => <tr key={`${batch.stockQuantId}:${batch.locationId}`}>
              <td>{batch.stockQuantId}</td><td>{batch.inDate}</td><td>{batch.expiryDate}</td><td>{batch.quantity}</td>
            </tr>)}</tbody>
          </table></div>}
        </article>)}
      </>}
    </section>
    <section><h3>Shipment</h3>
      {data.shipments.length === 0 ? <p>尚未建立 Shipment。</p> : data.shipments.map(shipment => <article className={styles.card} key={shipment.shipmentId}>
        <h4>{shipment.shipmentId === order.fulfilledByShipmentId ? '訂單履約所屬 Shipment' : 'Shipment'}</h4>
        <p>{shipment.status ?? '未知'} · 建立時間：{time(shipment.createdAt)}</p>
        <p>Shipment：{shipment.shipmentId}</p>
        <p>作業：{shipment.stockOperationId}</p>
        <p>品項 {shipment.lines.length} 行 · 揀貨 {shipment.pickTasks.filter(t => t.status === 'PICKED').length}／{shipment.pickTasks.length} 筆</p>
      </article>)}
    </section>
    {data.orchestrationMode === 'temporal' ? <section><h3>Temporal Workflow</h3>
      <p>查詢狀態：{data.workflowQueryStatus}</p>
      {workflow ? <dl className={styles.facts}>
        <dt>目前階段</dt><dd>{workflow.phase ?? '未知'}</dd>
        <dt>Outcome</dt><dd>{workflow.outcome ?? '尚無最終結果'}</dd>
        <dt>目前階段進入時間</dt><dd>{time(workflow.updatedAt)}</dd>
        <dt>分配</dt><dd>{workflow.allocationState ?? '—'}</dd>
        <dt>交接終態</dt><dd>{workflow.shipmentTerminalStatus ?? '—'}</dd>
      </dl> : <p>{data.workflowQueryStatus === 'NOT_FOUND' ? '查無 Workflow，可能尚未建立；不保證稍後一定啟動。' : '目前無可讀的 Workflow 快照。'}</p>}
    </section> : null}
  </>;
}
