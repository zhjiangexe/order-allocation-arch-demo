import { useCallback } from 'react';

import { getStockInLocation, confirmStockReceipt } from '../api/client';
import { facilityStockLines, type StockLine } from '../api/stockLines';
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
      async (ownerId, locationId) =>
        facilityStockLines(catalog, ownerId, await getStockInLocation(ownerId, locationId)),
      [catalog],
    ),
  );

  const receiptConfirmation = useAsyncAction(confirmStockReceipt);

  return (
    <div className={styles.page}>
      <section className={styles.section}>
        <h2 className={styles.sectionHeading}>庫存與收貨確認</h2>
        <StockPanel
          lines={lines.state}
          receiptConfirmation={receiptConfirmation.state}
          owners={catalog.owners}
          facilitiesOf={(ownerId) => catalog.facilitiesOf(ownerId)}
          locationsOf={(facilityId) => catalog.locationsOf(facilityId)}
          onQuery={(ownerId, _facilityId, locationId) => void lines.run(ownerId, locationId)}
          // 收貨本身同步完成，但缺貨訂單可能在同一交易立即吃掉新量；保留手動重查，讓使用者
          // 決定何時刷新這份查詢快照。
          onConfirmReceipt={(input) => void receiptConfirmation.run({
            ...input,
            receiptId: crypto.randomUUID(),
          })}
          onScopeChange={() => {
            lines.reset();
            receiptConfirmation.reset();
          }}
        />
      </section>
    </div>
  );
}
