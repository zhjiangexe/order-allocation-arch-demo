import { useId, useState } from 'react';

import type { StockLine } from '../api/stockLines';
import styles from './ReplenishDialog.module.css';

export interface ReplenishInput {
  ownerId: string;
  facilityId: string;
  sku: string;
  inDate: string;
  expiryDate: string;
  quantity: number;
}

interface ReplenishDialogProps {
  line: StockLine;
  /** 唯讀顯示用。識別碼由 {@link ReplenishDialogProps.onSubmit} 那一側補上。 */
  ownerName: string;
  nodeName: string;
  onSubmit: (input: Omit<ReplenishInput, 'ownerId' | 'facilityId'>) => void;
  onClose: () => void;
}

/**
 * 從某一列開啟的補貨視窗。
 *
 * <p><b>貨主、倉別、貨品唯讀。</b>三者已經由查詢與被點的那一列決定；讓它們可改就會出現一個
 * 矛盾狀態——你點的是北部倉的烏龍茶，寫進去的卻是中部倉，而送出後列表不會動、畫面也沒有東西
 * 解釋為什麼。要補別的倉就把上面的倉別下拉換掉，一步。
 *
 * <p><b>但三者仍然顯示出來。</b>補貨要五個維度才決定得了寫進哪一列，把已知的三個藏起來會讓
 * 「這一次補貨到底落在哪」變成要從畫面別處推。唯讀顯示讓五個都看得到，同時讓其中三個不可能
 * 與你點的那一列矛盾。
 *
 * <p><b>兩個日期預帶該規格最近效期、且還沒過期的那一批。</b>預設值是在替使用者選意圖：不改
 * 直接送就是往現有的批加貨，改掉日期才是開一批新的。刻意跳過已過期的批——那一批配貨永遠取
 * 不到，往它加貨等於製造看不見的死庫存。全部過期或一批都沒有時兩欄留空。
 */
export function ReplenishDialog({
  line,
  ownerName,
  nodeName,
  onSubmit,
  onClose,
}: ReplenishDialogProps) {
  const titleId = useId();
  const inDateId = useId();
  const expiryDateId = useId();
  const quantityId = useId();

  // 批已經是 FEFO 順序，所以第一個沒過期的就是最近效期那一批。
  const target = line.batches.find((batch) => !batch.expired);
  const [inDate, setInDate] = useState(target?.inDate ?? '');
  const [expiryDate, setExpiryDate] = useState(target?.expiryDate ?? '');
  const [quantity, setQuantity] = useState('500');

  const parsedQuantity = Number(quantity);
  // 缺任一個就不送。無效的補貨不該換來一次沒有必要的往返。
  const canSubmit =
    inDate !== '' &&
    expiryDate !== '' &&
    Number.isInteger(parsedQuantity) &&
    parsedQuantity > 0;

  return (
    <div className={styles.backdrop}>
      <div className={styles.dialog} role="dialog" aria-modal="true" aria-labelledby={titleId}>
        <h3 className={styles.title} id={titleId}>補貨</h3>

        <dl className={styles.context}>
          <div>
            <dt>貨主</dt>
            <dd>{ownerName}</dd>
          </div>
          <div>
            <dt>倉別</dt>
            <dd>{nodeName}</dd>
          </div>
          <div>
            <dt>貨品</dt>
            <dd>
              {line.sku === undefined
                ? line.skuCode
                : `${line.sku.productName}・${line.sku.specName}`}{' '}
              <span className={styles.skuValue}>{line.skuCode}</span>
            </dd>
          </div>
        </dl>

        <p className={styles.note}>
          {target === undefined
            ? '這個倉還沒有這個貨品，兩個日期填什麼就開一批新的。'
            : '日期預帶最近效期那一批——不改直接送就是往它加貨，改掉才是開一批新的。'}
        </p>

        <div className={styles.fields}>
          <div className={styles.field}>
            <label className={styles.label} htmlFor={inDateId}>入庫日</label>
            <input
              id={inDateId}
              className={styles.input}
              type="date"
              value={inDate}
              onChange={(event) => setInDate(event.target.value)}
            />
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor={expiryDateId}>效期</label>
            <input
              id={expiryDateId}
              className={styles.input}
              type="date"
              value={expiryDate}
              onChange={(event) => setExpiryDate(event.target.value)}
            />
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
        </div>

        <div className={styles.actions}>
          <button type="button" onClick={onClose}>取消</button>
          <button
            type="button"
            disabled={!canSubmit}
            onClick={() =>
              onSubmit({ sku: line.skuCode, inDate, expiryDate, quantity: parsedQuantity })
            }
          >
            送出補貨
          </button>
        </div>
      </div>
    </div>
  );
}
