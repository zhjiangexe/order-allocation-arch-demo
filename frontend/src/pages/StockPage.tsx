import { useCallback } from 'react';

import { getStockInWarehouse, replenish } from '../api/client';
import { warehouseStockLines, type StockLine } from '../api/stockLines';
import { StockPanel } from '../components/StockPanel';
import { useAsyncAction } from '../hooks/useAsyncAction';
import { useCatalog } from '../hooks/useCatalog';
import styles from './OrdersPage.module.css';

export function StockPage() {
  const catalog = useCatalog();

  /**
   * 查回來的分組在這裡就與主檔併成列表，不交給元件。
   *
   * 那個 join 需要主檔與後端回應兩半，而主檔已經在這一層；讓 `StockPanel` 自己併等於把主檔
   * 再往下傳一層，只為了做同一件事。元件因此只收「已經是列表」的東西，它的測試也不必為了驗
   * 畫面而先組一份主檔。
   */
  const lines = useAsyncAction<[string, string], StockLine[]>(
    useCallback(
      async (ownerId, facilityId) =>
        warehouseStockLines(catalog, ownerId, await getStockInWarehouse(ownerId, facilityId)),
      [catalog],
    ),
  );

  const replenishment = useAsyncAction(replenish);

  return (
    <div className={styles.page}>
      <section className={styles.section}>
        <h2 className={styles.sectionHeading}>庫存與補貨</h2>
        <StockPanel
          lines={lines.state}
          replenishment={replenishment.state}
          owners={catalog.owners}
          facilitiesOf={(ownerId) => catalog.facilitiesOf(ownerId)}
          onQuery={(ownerId, facilityId) => void lines.run(ownerId, facilityId)}
          // **刻意不在成功後自動重查。** 補貨回 202，庫存變更走 Kafka——立刻重查很可能查到
          // 還沒變的數字，而畫面分不出「還沒處理到」與「處理完了但真的沒變」。
          onReplenish={(input) => void replenishment.run(input)}
          onScopeChange={() => {
            lines.reset();
            replenishment.reset();
          }}
        />
      </section>
    </div>
  );
}
