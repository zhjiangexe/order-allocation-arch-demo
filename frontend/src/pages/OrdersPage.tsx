import { useEffect } from 'react';

import { listRecentOrders, placeOrder } from '../api/client';
import type { PlaceOrderCommand } from '../api/types';
import { ActionState } from '../components/ActionState';
import { OrderTable } from '../components/OrderTable';
import { PlaceOrderForm } from '../components/PlaceOrderForm';
import { useAsyncAction } from '../hooks/useAsyncAction';
import styles from './OrdersPage.module.css';

const LIST_LIMIT = 20;

export function OrdersPage() {
  const orders = useAsyncAction(listRecentOrders);
  const placement = useAsyncAction(placeOrder);
  const { run: loadOrders } = orders;

  // 全應用只有兩個 effect，都是「進場載入」。其餘取數一律在 event handler——
  // 多一個 effect 就會破壞規格「靜置的操作台不發出任何請求」的保證。
  useEffect(() => {
    void loadOrders(LIST_LIMIT);
  }, [loadOrders]);

  async function handlePlaceOrder(command: PlaceOrderCommand) {
    const placed = await placement.run(command);
    if (placed !== null) {
      // 下單成功才重抓：失敗時列表維持原狀，不會讓人以為畫面已經反映了這次操作
      await loadOrders(LIST_LIMIT);
    }
  }

  return (
    <div className={styles.page}>
      <section className={styles.section}>
        <h2 className={styles.sectionHeading}>下單</h2>
        <PlaceOrderForm onSubmit={handlePlaceOrder} pending={placement.state.status === 'pending'} />
        <ActionState state={placement.state} pendingLabel="下單中…">
          {() => null}
        </ActionState>
      </section>

      <section className={styles.section}>
        <h2 className={styles.sectionHeading}>
          最近 {LIST_LIMIT} 筆訂單
          <button
            type="button"
            className={styles.refresh}
            onClick={() => void loadOrders(LIST_LIMIT)}
            disabled={orders.state.status === 'pending'}
          >
            重新整理
          </button>
        </h2>
        <p className={styles.sectionHeading}>
          配置是非同步的：觸發補貨後要按重新整理才看得到狀態變化。
        </p>
        <ActionState state={orders.state} pendingLabel="載入訂單中…">
          {(list) => <OrderTable orders={list} />}
        </ActionState>
      </section>
    </div>
  );
}
