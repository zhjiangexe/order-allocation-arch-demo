import { useId, useState } from 'react';

import type { StockLine } from '../api/stockLines';
import styles from './ConfirmStockReceiptDialog.module.css';

export interface ConfirmStockReceiptInput {
  ownerId: string;
  facilityId: string;
  locationId: string;
  sku: string;
  inDate: string;
  expiryDate: string;
  quantity: number;
}

interface ConfirmStockReceiptDialogProps {
  line: StockLine;
  /** 唯讀顯示用。識別碼由 {@link ConfirmStockReceiptDialogProps.onSubmit} 那一側補上。 */
  ownerName: string;
  facilityName: string;
  locationName: string;
  onSubmit: (input: Omit<ConfirmStockReceiptInput, 'ownerId' | 'facilityId' | 'locationId'>) => void;
  onClose: () => void;
}

/**
 * 從某一列開啟的單次收貨確認視窗。
 *
 * <p><b>貨主、設施、庫位、貨品唯讀。</b>四者已經由查詢與被點的那一列決定；讓它們可改就會出現一個
 * 矛盾狀態——你點的是北部倉的烏龍茶，寫進去的卻是中部倉，而送出後列表不會動、畫面也沒有東西
 * 解釋為什麼。要補別的倉就把上面的設施下拉換掉，一步。
 *
 * <p><b>但四者仍然顯示出來。</b>收貨要五個庫存身分維度才決定得了寫進哪一列，把已知的三個
 * 身分維度與 Facility 藏起來會讓「這一次收貨到底落在哪」變成要從畫面別處推。唯讀顯示讓
 * 它們都看得到，同時讓它們不可能
 * 與你點的那一列矛盾。
 *
 * <p><b>兩個日期預帶該規格最近效期、且還沒過期的那一批。</b>預設值是在替使用者選意圖：不改
 * 直接送就是往現有的批加貨，改掉日期才是開一批新的。刻意跳過已過期的批——那一批配貨永遠取
 * 不到，往它加貨等於製造看不見的死庫存。全部過期或一批都沒有時兩欄留空。
 */
export function ConfirmStockReceiptDialog({
  line,
  ownerName,
  facilityName,
  locationName,
  onSubmit,
  onClose,
}: ConfirmStockReceiptDialogProps) {
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
  // 缺任一個就不送。無效的收貨不該換來一次沒有必要的往返。
  const canSubmit =
    inDate !== '' &&
    expiryDate !== '' &&
    Number.isInteger(parsedQuantity) &&
    parsedQuantity > 0;

  return (
    <div className={styles.backdrop}>
      <div className={styles.dialog} role="dialog" aria-modal="true" aria-labelledby={titleId}>
        <h3 className={styles.title} id={titleId}>確認收貨</h3>

        <dl className={styles.context}>
          <div>
            <dt>貨主</dt>
            <dd>{ownerName}</dd>
          </div>
          <div>
            <dt>設施</dt>
            <dd>{facilityName}</dd>
          </div>
          <div>
            <dt>庫位</dt>
            <dd>{locationName}</dd>
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
            ? '這個庫位還沒有這個貨品，兩個日期填什麼就開一批新的。'
            : '日期預帶最近效期那一批——不改直接送就是往它加貨，改掉才是開一批新的。'}
        </p>

        <div className={styles.fields}>
          <div className={styles.field}>
            <label className={styles.label} htmlFor={inDateId}>收貨日</label>
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
            <label className={styles.label} htmlFor={quantityId}>實收數量</label>
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
            確認收貨
          </button>
        </div>
      </div>
    </div>
  );
}
