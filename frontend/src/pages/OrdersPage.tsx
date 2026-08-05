import { useEffect } from 'react';

import { listRecentOrders, placeOrder } from '../api/client';
import type { PlaceOrderCommand } from '../api/types';
import { ActionState } from '../components/ActionState';
import { OrderTable } from '../components/OrderTable';
import { PlaceOrderForm } from '../components/PlaceOrderForm';
import { useAsyncAction } from '../hooks/useAsyncAction';
import { useCatalog } from '../hooks/useCatalog';
import styles from './OrdersPage.module.css';

const LIST_LIMIT = 20;

export function OrdersPage() {
  const catalog = useCatalog();
  const orders = useAsyncAction(listRecentOrders);
  const placement = useAsyncAction(placeOrder);
  const { run: loadOrders } = orders;

  // effect 只用於「進場載入」與「選擇驅動的主檔查詢」（見 useCatalog 與 PlaceOrderForm），
  // 其餘取數一律在 event handler。任何帶 interval 或 timeout 的 effect 都會破壞規格
  // 「靜置的操作台不發出任何請求」的保證——那是這裡唯一不能加的東西。
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
        <PlaceOrderForm
          catalog={catalog}
          onSubmit={handlePlaceOrder}
          pending={placement.state.status === 'pending'}
        />
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
          配置是非同步的：觸發收貨後要按重新整理才看得到狀態變化。
        </p>
        <ActionState state={orders.state} pendingLabel="載入訂單中…">
          {(list) => (
            <OrderTable orders={list} catalog={catalog} />
          )}
        </ActionState>
      </section>
    </div>
  );
}
