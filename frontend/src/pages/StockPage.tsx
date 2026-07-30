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
          owners={catalog.owners}
          nodesOf={(ownerId) => catalog.nodesOf(ownerId)}
          skuCodesOf={(ownerId) => catalog.skuCodesOf(ownerId)}
          // 查不到就顯示 id：主檔缺一筆不該讓整列變空白，而 id 至少還查得下去。
          nodeLabel={(ownerId, nodeId) => catalog.findNode(ownerId, nodeId)?.name ?? nodeId}
          onQuery={(ownerId, sku) => void stock.run(ownerId, sku)}
          onReplenish={(input) => void replenishment.run(input)}
          onScopeChange={() => {
            stock.reset();
            replenishment.reset();
          }}
        />
      </section>
    </div>
  );
}
