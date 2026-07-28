import { useId, useState } from 'react';

import type { OwnerView, ReplenishmentAccepted, StockPoolView } from '../api/types';
import type { AsyncState } from '../hooks/useAsyncAction';
import { ActionState } from './ActionState';
import styles from './StockPanel.module.css';

interface StockPanelProps {
  stock: AsyncState<StockPoolView>;
  replenishment: AsyncState<ReplenishmentAccepted>;
  owners: OwnerView[];
  /** 主檔裡的 SKU 代碼，作為輸入建議。刻意不是限制——見下方 datalist 的說明。 */
  skuCodes: string[];
  onQuery: (sku: string) => void;
  onReplenish: (ownerId: string, sku: string, quantity: number) => void;
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
 *
 * <p><b>補貨要選貨主，查詢不用</b>，而這個不對稱是真的：補貨要喚醒的是某個貨主的缺貨佇列，
 * 而 SKU 代碼跨貨主撞號、決定不了是誰的；庫存本身卻還沒有貨主維度，兩個貨主的同碼 SKU 共用
 * 同一列，查詢加上貨主等於報告一個資料裡不存在的區別。畫面上直說這件事，比讓人自己推敲
 * 為什麼一邊要選一邊不用好。
 */
export function StockPanel({
  stock,
  replenishment,
  owners,
  skuCodes,
  onQuery,
  onReplenish,
  onSkuChange,
}: StockPanelProps) {
  const skuId = useId();
  const skuOptionsId = useId();
  const quantityId = useId();
  const ownerId = useId();
  const [sku, setSku] = useState('');
  const [quantity, setQuantity] = useState('500');
  const [selectedOwner, setSelectedOwner] = useState('');

  const trimmedSku = sku.trim();
  const parsedQuantity = Number(quantity);
  const canQuery = trimmedSku !== '';
  const canReplenish =
    canQuery && selectedOwner !== '' && Number.isInteger(parsedQuantity) && parsedQuantity > 0;

  return (
    <div className={styles.panel}>
      {/*
        這段必須在查詢之前就在畫面上：使用者面對「補貨要選貨主、查詢不用」的疑問是在按任何
        按鈕之前。同樣的說明在查詢結果裡還會再出現一次，那一次是為了限定那組數字的歸屬，
        兩者的時機與對象不同，不是重複。
      */}
      <p className={styles.scopeNote}>
        補貨要選貨主——它喚醒的是某個貨主的缺貨佇列，而 SKU 代碼跨貨主撞號、決定不了是誰的。
        查詢不用選：庫存尚未按貨主分開，加上貨主等於報告一個資料裡不存在的區別。
      </p>
      <div className={styles.controls}>
        <div className={styles.field}>
          <label className={styles.label} htmlFor={skuId}>SKU</label>
          <input
            id={skuId}
            className={styles.input}
            list={skuOptionsId}
            value={sku}
            onChange={(event) => {
              setSku(event.target.value);
              onSkuChange();
            }}
            placeholder="HOT-SKU"
          />
          {/*
            用 datalist 給建議而不是換成下拉：庫存池與主檔不是同一組資料，壓測用的 HOT-SKU
            有庫存池卻沒有主檔，改成只能選就會讓它查不到。代碼已跨貨主去重，因為庫存池還沒
            有貨主維度，同碼 SKU 對查詢而言本來就是同一列。
          */}
          <datalist id={skuOptionsId}>
            {skuCodes.map((code) => (
              <option key={code} value={code} />
            ))}
          </datalist>
        </div>
        <button type="button" onClick={() => onQuery(trimmedSku)} disabled={!canQuery}>
          查詢庫存
        </button>
        <div className={styles.field}>
          <label className={styles.label} htmlFor={ownerId}>補貨貨主</label>
          <select
            id={ownerId}
            className={styles.input}
            value={selectedOwner}
            onChange={(event) => setSelectedOwner(event.target.value)}
          >
            <option value="">請選擇</option>
            {owners.map((owner) => (
              <option key={owner.ownerId} value={owner.ownerId}>
                {owner.name}（{owner.code}）
              </option>
            ))}
          </select>
        </div>
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
          onClick={() => onReplenish(selectedOwner, trimmedSku, parsedQuantity)}
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
            <p className={styles.resultFor}>
              庫存尚未按貨主分開——兩個貨主的同碼 SKU 共用這一列，因此下方數字不屬於任何
              單一貨主。
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
