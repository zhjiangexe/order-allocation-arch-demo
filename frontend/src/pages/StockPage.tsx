import { getStockPool, replenish } from '../api/client';
import { StockPanel } from '../components/StockPanel';
import { useAsyncAction } from '../hooks/useAsyncAction';
import { useCatalog } from '../hooks/useCatalog';
import styles from './OrdersPage.module.css';

export function StockPage() {
  const catalog = useCatalog();
  const stock = useAsyncAction(getStockPool);
  const replenishment = useAsyncAction(replenish);

  return (
    <div className={styles.page}>
      <section className={styles.section}>
        <h2 className={styles.sectionHeading}>庫存與補貨</h2>
        <StockPanel
          stock={stock.state}
          replenishment={replenishment.state}
          onQuery={(sku) => void stock.run(sku)}
          owners={catalog.owners}
          skuCodes={catalog.skuCodes()}
          onReplenish={(ownerId, sku, quantity) =>
            void replenishment.run({ ownerId, sku, quantity })
          }
          onSkuChange={() => {
            stock.reset();
            replenishment.reset();
          }}
        />
      </section>
    </div>
  );
}
