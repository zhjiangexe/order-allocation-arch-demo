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
  /** 改動 SKU 時作廢畫面上的結果——它們屬於上一個 SKU。 */
  onSkuChange: () => void;
}

/**
 * 查庫存與補貨放同一個面板、共用同一個 SKU 欄位，因為它們是一個連續動作：查到 ATP 是 0、
 * 補一批、再查一次確認。拆成兩塊會把這條敘事切斷。
 *
 * <p>共用一個欄位的代價是結果會失去歸屬：查完一個 SKU、把欄位改成另一個，畫面上的數字
 * 仍是前一個 SKU 的。因此兩件事都要做——結果自己標出它屬於哪個 SKU（取自後端回應，不是
 * 輸入框，兩者在查詢往返之間可能已經不同），而且改動欄位就作廢前一次的結果。
 */
export function StockPanel({
  stock,
  replenishment,
  onQuery,
  onReplenish,
  onSkuChange,
}: StockPanelProps) {
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
            onChange={(event) => {
              setSku(event.target.value);
              onSkuChange();
            }}
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
          <div className={styles.result}>
            <p className={styles.resultFor}>
              <span className={styles.skuValue}>{pool.sku}</span> 的庫存
            </p>
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
          </div>
        )}
      </ActionState>

      <ActionState state={replenishment} pendingLabel="發布補貨事件中…">
        {(accepted) => (
          <p className={styles.accepted}>
            <span className={styles.skuValue}>{accepted.sku}</span> 的補貨事件已受理，事件識別碼{' '}
            <span className={styles.eventId}>{accepted.eventId}</span>
            。配置是非同步的——再查一次庫存、或到訂單頁按重新整理，才看得到結果。
          </p>
        )}
      </ActionState>
    </div>
  );
}
