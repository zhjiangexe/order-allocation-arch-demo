import type { OrderView } from '../api/types';
import styles from './OrderTable.module.css';

interface OrderTableProps {
  orders: OrderView[];
}

/**
 * 一列攤開訂單表示的全部欄位，包含四個階段時間戳。刻意不做點開的詳細檢視——訂單只有
 * 這八個欄位，第二層視圖會是同一份資料的第二次呈現。
 */
export function OrderTable({ orders }: OrderTableProps) {
  if (orders.length === 0) {
    return <p className={styles.empty}>目前沒有訂單。用上方的表單下一張。</p>;
  }

  return (
    <div className={styles.scroller}>
      <table className={styles.table}>
        <thead>
          <tr>
            <th>order</th>
            <th>sku</th>
            <th>qty</th>
            <th>status</th>
            <th>placed</th>
            <th>allocated</th>
            <th>backordered</th>
            <th>cancelled</th>
          </tr>
        </thead>
        <tbody>
          {orders.map((order) => (
            <tr key={order.orderId}>
              <td className={styles.id}>{order.orderId.slice(0, 8)}</td>
              <td>{order.sku}</td>
              <td>{order.quantity}</td>
              <td className={`${styles.status} ${styles[order.status]}`}>{order.status}</td>
              <td>{formatTime(order.placedAt)}</td>
              <td>{formatTime(order.allocatedAt)}</td>
              <td>{formatTime(order.backOrderedSince)}</td>
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
