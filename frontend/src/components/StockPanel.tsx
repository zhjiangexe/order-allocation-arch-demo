import { useId, useState } from 'react';

import type { ReplenishmentAccepted, StockPoolView } from '../api/types';
import type { AsyncState } from '../hooks/useAsyncAction';
import { ActionState } from './ActionState';
import styles from './StockPanel.module.css';

interface StockPanelProps {
  stock: AsyncState<StockPoolView>;
  replenishment: AsyncState<ReplenishmentAccepted>;
  onQuery: (sku: string) => void;
  onReplenish: (sku: string, quantity: number) => void;
}

/**
 * 查庫存與補貨放同一個面板、共用同一個 SKU 欄位，因為它們是一個連續動作：查到 ATP 是 0、
 * 補一批、再查一次確認。拆成兩塊會把這條敘事切斷。
 */
export function StockPanel({ stock, replenishment, onQuery, onReplenish }: StockPanelProps) {
  const skuId = useId();
  const quantityId = useId();
  const [sku, setSku] = useState('');
  const [quantity, setQuantity] = useState('500');

  const trimmedSku = sku.trim();
  const parsedQuantity = Number(quantity);
  const canQuery = trimmedSku !== '';
  const canReplenish = canQuery && Number.isInteger(parsedQuantity) && parsedQuantity > 0;

  return (
    <div className={styles.panel}>
      <div className={styles.controls}>
        <div className={styles.field}>
          <label className={styles.label} htmlFor={skuId}>SKU</label>
          <input
            id={skuId}
            className={styles.input}
            value={sku}
            onChange={(event) => setSku(event.target.value)}
            placeholder="HOT-SKU"
          />
        </div>
        <button type="button" onClick={() => onQuery(trimmedSku)} disabled={!canQuery}>
          查詢庫存
        </button>
        <div className={styles.field}>
          <label className={styles.label} htmlFor={quantityId}>補貨數量</label>
          <input
            id={quantityId}
            className={`${styles.input} ${styles.quantity}`}
            value={quantity}
            onChange={(event) => setQuantity(event.target.value)}
            inputMode="numeric"
          />
        </div>
        <button
          type="button"
          onClick={() => onReplenish(trimmedSku, parsedQuantity)}
          disabled={!canReplenish}
        >
          觸發補貨
        </button>
      </div>

      <ActionState state={stock} pendingLabel="查詢中…">
        {(pool) => (
          <dl className={styles.quantities}>
            <div>
              <dt>on-hand</dt>
              <dd>{pool.onHandQuantity}</dd>
            </div>
            <div>
              <dt>reserved</dt>
              <dd>{pool.reservedQuantity}</dd>
            </div>
            <div>
              <dt>available-to-promise</dt>
              <dd>{pool.availableToPromise}</dd>
            </div>
          </dl>
        )}
      </ActionState>

      <ActionState state={replenishment} pendingLabel="發布補貨事件中…">
        {(accepted) => (
          <p className={styles.accepted}>
            補貨事件已受理，事件識別碼{' '}
            <span className={styles.eventId}>{accepted.eventId}</span>
            。配置是非同步的——再查一次庫存、或到訂單頁按重新整理，才看得到結果。
          </p>
        )}
      </ActionState>
    </div>
  );
}
