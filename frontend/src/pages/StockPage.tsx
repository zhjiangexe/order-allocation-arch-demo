import { getStockPool, replenish } from '../api/client';
import { StockPanel } from '../components/StockPanel';
import { useAsyncAction } from '../hooks/useAsyncAction';
import styles from './OrdersPage.module.css';

export function StockPage() {
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
          onReplenish={(sku, quantity) => void replenishment.run({ sku, quantity })}
        />
      </section>
    </div>
  );
}
