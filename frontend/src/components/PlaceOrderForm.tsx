import { useId, useState } from 'react';

import type { Catalog } from '../api/catalog';
import type { PlaceOrderCommand } from '../api/types';
import styles from './PlaceOrderForm.module.css';

interface PlaceOrderFormProps {
  catalog: Catalog;
  onSubmit: (command: PlaceOrderCommand) => void;
  pending: boolean;
  blocked?: boolean;
}

/** 表單裡的一條行：選到一半也是合法的中間狀態，所以三個欄位都可以是空的。 */
interface DraftLine {
  /** 只給 React 當 key 用。行號由後端依序產生，不由這裡決定。 */
  key: number;
  productCode: string;
  skuCode: string;
  quantity: string;
}

/**
 * 一張訂單是一籃行，而不是一件商品。
 *
 * 商品以「款 → 規格」兩段選擇，而不是輸入 SKU 代碼。在 3PL 裡 SKU 代碼由貨主自訂、跨貨主
 * 撞號，讓人手打就可能送出屬於別的貨主的代碼——那會被資料庫的外鍵擋下，但那是在一次無謂的
 * 往返之後。逐層選擇讓那種輸入從一開始就不存在。
 *
 * 切換貨主時清空倉庫與**每一條行**：三者在別的貨主底下都不成立，留著只會讓表單能送出跨貨主
 * 的組合。倉庫尤其要清——兩個貨主可能共用同一個倉，留著看起來像「還有效」，但它是否有效取決
 * 於指派關係。
 *
 * 倉庫同樣用選的不用打——打字可以打出該貨主沒掛的倉，那會被資料庫的複合外鍵擋下，換來一次
 * 沒有必要的往返。
 *
 * 三層選項都讀 `catalog`，不自己發請求：訂單列表為了把代碼還原成看得懂的字，本來就已經把整份
 * 主檔載進來了，表單再抓一次只會抓到同一批資料。因此選貨主、選款都不產生任何往返。
 *
 * 同一個規格出現在兩條行上是允許的，不是被容忍的：收單接受它，需求讀成兩者的加總。在這裡擋
 * 下只會讓操作台拒絕系統處理得了的訂單。
 */
export function PlaceOrderForm({ catalog, onSubmit, pending, blocked = false }: PlaceOrderFormProps) {
  const ownerId = useId();
  const facilityId = useId();
  const externalOrderNoId = useId();
  const lineFieldId = useId();
  const zoneId = useId();
  const addressId = useId();
  const promisedId = useId();
  const dispatchId = useId();
  const priorityId = useId();
  const [dispatchBy, setDispatchBy] = useState('');
  const [releasePriority, setReleasePriority] = useState('0');

  const [selectedOwner, setSelectedOwner] = useState('');
  const [selectedFacility, setSelectedFacility] = useState('');
  const [externalOrderNo, setExternalOrderNo] = useState('');
  const [lines, setLines] = useState<DraftLine[]>([emptyLine(0)]);
  const [nextKey, setNextKey] = useState(1);
  const [shipToZone, setShipToZone] = useState('100');
  const [shipToAddress, setShipToAddress] = useState('台北市中正區重慶南路一段 122 號');
  const [promisedDeliveryDate, setPromisedDeliveryDate] = useState(defaultPromisedDate());
  const [invalidReason, setInvalidReason] = useState<string | null>(null);

  const facilities = catalog.facilitiesOf(selectedOwner);
  const products = catalog.productsOf(selectedOwner);

  function handleOwnerChange(value: string) {
    setSelectedOwner(value);
    setSelectedFacility('');
    // 每一條行都清掉，不只第一條——留下任何一條都等於留著一個屬於別的貨主的商品。
    setLines([emptyLine(nextKey)]);
    setNextKey(nextKey + 1);
  }

  function updateLine(key: number, patch: Partial<DraftLine>) {
    setLines(lines.map((line) => (line.key === key ? { ...line, ...patch } : line)));
  }

  function addLine() {
    setLines([...lines, emptyLine(nextKey)]);
    setNextKey(nextKey + 1);
  }

  function removeLine(key: number) {
    // 最後一條留著：沒有需求的訂單送不出去，把行數歸零只會讓表單進入一個無法送出的狀態。
    if (lines.length === 1) {
      return;
    }
    setLines(lines.filter((line) => line.key !== key));
  }

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    if (pending || blocked) return;
    const reason = validate(selectedOwner, selectedFacility, externalOrderNo, lines)
      ?? (!shipToZone.trim() ? '配送分區不可為空' : null)
      ?? (!shipToAddress.trim() ? '收件地址不可為空' : null)
      ?? (!promisedDeliveryDate ? '請填寫承諾到貨日' : null)
      ?? (!dispatchBy || Number.isNaN(new Date(dispatchBy).getTime()) ? '請填寫有效的最晚離倉時間' : null)
      ?? (!releasePriority.trim() || !Number.isInteger(Number(releasePriority))
        || Number(releasePriority) < 0 || Number(releasePriority) > 100 ? '出庫釋放優先級必須為 0～100 的整數' : null);
    setInvalidReason(reason);
    if (reason !== null) {
      return;
    }
    onSubmit({
      ownerId: selectedOwner,
      facilityId: selectedFacility,
      externalOrderNo: externalOrderNo.trim(),
      shipToZone: shipToZone.trim(),
      shipToAddress: shipToAddress.trim(),
      promisedDeliveryDate,
      dispatchBy: new Date(dispatchBy).toISOString(),
      releasePriority: Number(releasePriority),
      lines: lines.map((line) => ({
        skuCode: line.skuCode,
        quantity: Number(line.quantity),
      })),
    });
  }

  return (
    <form onSubmit={handleSubmit} noValidate>
      <fieldset className={styles.form} disabled={pending || blocked}>
      <legend className={styles.srOnly}>建立訂單</legend>
      <div className={styles.field}>
        <label className={styles.label} htmlFor={ownerId}>貨主</label>
        <select
          id={ownerId}
          className={styles.input}
          value={selectedOwner}
          onChange={(event) => handleOwnerChange(event.target.value)}
        >
          <option value="">請選擇</option>
          {catalog.owners.map((owner) => (
            <option key={owner.ownerId} value={owner.ownerId}>
              {owner.name}（{owner.code}）
            </option>
          ))}
        </select>
      </div>

      <div className={styles.field}>
        <label className={styles.label} htmlFor={facilityId}>履約設施</label>
        <select
          id={facilityId}
          className={styles.input}
          value={selectedFacility}
          onChange={(event) => setSelectedFacility(event.target.value)}
          disabled={selectedOwner === ''}
        >
          <option value="">請選擇</option>
          {facilities.map((facility) => (
            <option key={facility.facilityId} value={facility.facilityId}>
              {facility.name}（{facility.code}）
            </option>
          ))}
        </select>
      </div>

      <div className={styles.field}>
        <label className={styles.label} htmlFor={externalOrderNoId}>上游單號</label>
        <input
          id={externalOrderNoId}
          className={styles.input}
          value={externalOrderNo}
          onChange={(event) => setExternalOrderNo(event.target.value)}
          placeholder="PO-8891"
        />
      </div>

      <fieldset className={styles.lines}>
        <legend className={styles.label}>訂單行</legend>
        {lines.map((line, index) => (
          <div className={styles.line} key={line.key}>
            <div className={styles.field}>
              <label className={styles.label} htmlFor={`${lineFieldId}-product-${line.key}`}>
                第 {index + 1} 行・款
              </label>
              <select
                id={`${lineFieldId}-product-${line.key}`}
                className={styles.input}
                value={line.productCode}
                onChange={(event) =>
                  // 換款就清掉規格：舊的規格屬於舊的款，留著會送出兩者對不上的組合。
                  updateLine(line.key, { productCode: event.target.value, skuCode: '' })}
                disabled={selectedOwner === ''}
              >
                <option value="">請選擇</option>
                {products.map((product) => (
                  <option key={product.productId} value={product.productCode}>
                    {product.name}（{product.temperatureZone}）
                  </option>
                ))}
              </select>
            </div>

            <div className={styles.field}>
              <label className={styles.label} htmlFor={`${lineFieldId}-sku-${line.key}`}>
                第 {index + 1} 行・規格
              </label>
              <select
                id={`${lineFieldId}-sku-${line.key}`}
                className={styles.input}
                value={line.skuCode}
                onChange={(event) => updateLine(line.key, { skuCode: event.target.value })}
                disabled={line.productCode === ''}
              >
                <option value="">請選擇</option>
                {catalog.skusOf(selectedOwner, line.productCode).map((sku) => (
                  <option key={sku.skuId} value={sku.skuCode}>
                    {sku.specName}（{sku.skuCode}・{sku.weightGram}g）
                  </option>
                ))}
              </select>
            </div>

            <div className={styles.field}>
              <label className={styles.label} htmlFor={`${lineFieldId}-quantity-${line.key}`}>
                第 {index + 1} 行・數量
              </label>
              <input
                id={`${lineFieldId}-quantity-${line.key}`}
                className={`${styles.input} ${styles.quantity}`}
                value={line.quantity}
                onChange={(event) => updateLine(line.key, { quantity: event.target.value })}
                inputMode="numeric"
              />
            </div>

            <button
              type="button"
              className={styles.removeLine}
              onClick={() => removeLine(line.key)}
              disabled={lines.length === 1}
            >
              移除第 {index + 1} 行
            </button>
          </div>
        ))}
        <button type="button" onClick={addLine}>新增訂單行</button>
      </fieldset>

      <div className={styles.field}>
        <label className={styles.label} htmlFor={zoneId}>配送分區</label>
        <input
          id={zoneId}
          className={`${styles.input} ${styles.quantity}`}
          value={shipToZone}
          onChange={(event) => setShipToZone(event.target.value)}
        />
      </div>

      <div className={styles.field}>
        <label className={styles.label} htmlFor={addressId}>收件地址</label>
        <input
          id={addressId}
          className={styles.input}
          value={shipToAddress}
          onChange={(event) => setShipToAddress(event.target.value)}
        />
      </div>

      <div className={styles.field}>
        <label className={styles.label} htmlFor={promisedId}>承諾到貨日</label>
        <input
          id={promisedId}
          className={styles.input}
          type="date"
          value={promisedDeliveryDate}
          onChange={(event) => setPromisedDeliveryDate(event.target.value)}
        />
      </div>

      <div className={styles.field}>
        <label className={styles.label} htmlFor={dispatchId}>最晚離倉時間</label>
        <input id={dispatchId} className={styles.input} type="datetime-local" required
          value={dispatchBy} onChange={event => setDispatchBy(event.target.value)} />
        <small>時區：{Intl.DateTimeFormat().resolvedOptions().timeZone}</small>
      </div>
      <div className={styles.field}>
        <label className={styles.label} htmlFor={priorityId}>出庫釋放優先級</label>
        <input id={priorityId} className={`${styles.input} ${styles.quantity}`} type="number"
          min="0" max="100" step="1" required value={releasePriority}
          onChange={event => setReleasePriority(event.target.value)} />
        <small>0～100，預設 0</small>
      </div>
      <button type="submit" disabled={pending || blocked}>
        {pending ? '送出中…' : '送出訂單'}
      </button>
      {invalidReason === null ? null : (
        <p className={styles.error} role="alert">{invalidReason}</p>
      )}
      </fieldset>
    </form>
  );
}

/**
 * 送出前擋掉不完整的選擇與非正整數數量，這種輸入不發出請求——讓後端回 400 也行，但那會在
 * 操作台上製造一次沒有必要的往返，也讓「這是輸入錯誤」的回饋慢一拍。
 *
 * **任一條行不完整就擋下整張單**，而不是只送完整的那幾條：使用者填了那一條，就是要它。
 * 靜靜丟掉它送出其餘的，會讓對方拿到一張少東西的訂單卻以為送對了。
 */
function validate(
  ownerId: string,
  facilityId: string,
  externalOrderNo: string,
  lines: DraftLine[],
): string | null {
  if (ownerId === '') {
    return '請選擇貨主';
  }
  if (facilityId === '') {
    return '請選擇履約設施';
  }
  if (externalOrderNo.trim() === '') {
    return '上游單號不可為空';
  }
  for (const [index, line] of lines.entries()) {
    if (line.skuCode === '') {
      return `第 ${index + 1} 行：請選擇款與規格`;
    }
    const parsed = Number(line.quantity);
    if (line.quantity.trim() === '' || !Number.isInteger(parsed) || parsed <= 0) {
      return `第 ${index + 1} 行：數量必須是正整數`;
    }
  }
  return null;
}

function emptyLine(key: number): DraftLine {
  return { key, productCode: '', skuCode: '', quantity: '1' };
}

function defaultPromisedDate(): string {
  const inAWeek = new Date();
  inAWeek.setDate(inAWeek.getDate() + 7);
  return `${inAWeek.getFullYear()}-${String(inAWeek.getMonth() + 1).padStart(2, '0')}-${String(inAWeek.getDate()).padStart(2, '0')}`;
}
