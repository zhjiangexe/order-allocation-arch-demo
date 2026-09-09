import type { Catalog } from '../api/catalog';
import type { OrderView } from '../api/types';
import styles from './OrderTable.module.css';

interface OrderTableProps {
  orders: OrderView[];
  catalog: Catalog;
  onViewFulfillment?: (orderId: string) => void;
}

/**
 * 列表保留訂單摘要；履約入口另開跨 context 詳情。
 *
 * <p>一列對應一張單，行則在同一格內以頓號並列——**每一行的商品與數量都看得到**，不會只顯示
 * 第一行。多行訂單是整張配或整張不配，於是一張單可能在某個 SKU 還很充足時掛帳；看得出是哪
 * 一行卡住，那個「有貨卻不配」才讀得懂。
 *
 * <p>時間戳欄位依**生命週期順序**排列（placed → backordered → allocated → fulfilled／cancelled），
 * 不是照 `OrderStatusResponse` 的欄位順序。後端把 allocated 排在 backordered 前面，照抄
 * 會讓一張「下單→缺貨→收貨後配置」的訂單在畫面上讀起來像時間倒退。這幾欄是階段時間戳
 * 不是狀態，狀態只有 `status` 一欄——表頭加 `at` 就是為了讓這件事不需要解釋。
 */
export function OrderTable({ orders, catalog, onViewFulfillment }: OrderTableProps) {
  const ownerNames = new Map(catalog.owners.map((owner) => [owner.ownerId, owner.name]));

  /**
   * 「品名 · 規格」由前端從主檔解析，而不是讓訂單契約帶著它。
   *
   * 本頁為了下單表單的下拉選單已經載過主檔，這裡查的是同一份資料——那是「整個畫面查一次」，
   * 不是每一列各查一次。查不到回 `null`：代碼本來就會另外顯示，這時整格退化成只有代碼，
   * 也就是加上品名之前的樣子，不會變成空白，也不會把代碼印兩次。
   */
  function describe(order: OrderView, skuCode: string) {
    const sku = catalog.findSku(order.ownerId, skuCode);
    return sku === undefined ? null : `${sku.productName} · ${sku.specName}`;
  }

  if (orders.length === 0) {
    return <p className={styles.empty}>目前沒有訂單。按「新建訂單」建立第一張訂單。</p>;
  }

  return (
    <div className={styles.scroller}>
      <table className={styles.table}>
        <thead>
          <tr>
            <th>order</th>
            <th>履約</th>
            <th>owner</th>
            <th>item</th>
            <th>qty</th>
            <th>status</th>
            <th>received at</th>
            <th>placed upstream</th>
            <th>allocated at</th>
            <th>fulfilled at</th>
            <th>cancelled at</th>
          </tr>
        </thead>
        <tbody>
          {orders.map((order) => (
            <tr key={order.orderId}>
              <td className={styles.id}>{order.externalOrderNo}<br />{order.orderId.slice(-12)}</td>
              <td>{onViewFulfillment ? <button type="button" onClick={() => onViewFulfillment(order.orderId)}>查看履約</button> : '—'}</td>
              <td>{ownerNames.get(order.ownerId) ?? order.ownerId.slice(-12)}</td>
              <td>
                {order.lines.map((line) => (
                  <span key={line.lineNo} className={styles.line}>
                    {describe(order, line.skuCode)}
                    <span className={styles.lineSkuCode}>{line.skuCode}</span>
                  </span>
                ))}
              </td>
              <td>{order.lines.map((line) => line.quantity).join('、')}</td>
              <td className={`${styles.status} ${styles[order.status]}`}>{order.status}</td>
              <td>{formatTime(order.receivedAt)}</td>
              {/* 上游沒送時留白（—），與其他未發生的階段同一個表示法。不重複 receivedAt：
                  兩欄一樣的話，看的人分不出上游是真的送了還是我們補的。 */}
              <td>{formatTime(order.placedAt)}</td>
              <td>{formatTime(order.allocatedAt)}</td>
              <td>{formatTime(order.fulfilledAt)}</td>
              <td>{formatTime(order.cancelledAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** 未發生的階段留白（以 — 標示），不要顯示成 null 或空字串讓人以為是資料掉了。 */
function formatTime(value: string | null) {
  if (value === null) {
    return <span className={styles.absent}>—</span>;
  }
  return new Date(value).toLocaleTimeString('zh-TW', { hour12: false });
}
