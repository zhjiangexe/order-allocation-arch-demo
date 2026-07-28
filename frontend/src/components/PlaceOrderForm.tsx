import { useId, useState } from 'react';

import type { Catalog } from '../api/catalog';
import type { PlaceOrderCommand } from '../api/types';
import styles from './PlaceOrderForm.module.css';

interface PlaceOrderFormProps {
  catalog: Catalog;
  onSubmit: (command: PlaceOrderCommand) => void;
  pending: boolean;
}

/**
 * 商品以「款 → 規格」兩段選擇，而不是輸入 SKU 代碼。
 *
 * 在 3PL 裡 SKU 代碼由貨主自訂、跨貨主撞號，讓人手打就可能送出屬於別的貨主的代碼——那會被
 * 資料庫的外鍵擋下，但那是在一次無謂的往返之後。逐層選擇讓那種輸入從一開始就不存在。
 *
 * 切換貨主時清空倉庫、款與規格：三者在別的貨主底下都不成立，留著只會讓表單能送出跨貨主的
 * 組合。
 *
 * 倉庫同樣用選的不用打——打字可以打出該貨主沒掛的倉，那會被資料庫的複合外鍵擋下，換來
 * 一次沒有必要的往返。
 *
 * 三層選項都讀 `catalog`，不自己發請求：訂單列表為了把代碼還原成看得懂的字，本來就已經把
 * 整份主檔載進來了，表單再抓一次只會抓到同一批資料。因此選貨主、選款都不產生任何往返。
 */
export function PlaceOrderForm({ catalog, onSubmit, pending }: PlaceOrderFormProps) {
  const ownerId = useId();
  const nodeId = useId();
  const externalOrderNoId = useId();
  const productId = useId();
  const skuId = useId();
  const quantityId = useId();
  const zoneId = useId();
  const addressId = useId();
  const promisedId = useId();

  const [selectedOwner, setSelectedOwner] = useState('');
  const [selectedNode, setSelectedNode] = useState('');
  const [externalOrderNo, setExternalOrderNo] = useState('');
  const [selectedProduct, setSelectedProduct] = useState('');
  const [selectedSku, setSelectedSku] = useState('');
  const [quantity, setQuantity] = useState('1');
  const [shipToZone, setShipToZone] = useState('100');
  const [shipToAddress, setShipToAddress] = useState('台北市中正區重慶南路一段 122 號');
  const [promisedDeliveryDate, setPromisedDeliveryDate] = useState(defaultPromisedDate());
  const [invalidReason, setInvalidReason] = useState<string | null>(null);

  const nodes = catalog.nodesOf(selectedOwner);
  const products = catalog.productsOf(selectedOwner);
  const skus = catalog.skusOf(selectedOwner, selectedProduct);

  function handleOwnerChange(value: string) {
    setSelectedOwner(value);
    // 換貨主等於換一整組主檔——倉庫、款、規格在新貨主底下都不成立。倉庫尤其要清：
    // 兩個貨主可能共用同一個倉，留著看起來像「還有效」，但它是否有效取決於指派關係。
    setSelectedNode('');
    setSelectedProduct('');
    setSelectedSku('');
  }

  function handleProductChange(value: string) {
    setSelectedProduct(value);
    setSelectedSku('');
  }

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    const reason = validate(selectedOwner, selectedNode, externalOrderNo, selectedSku, quantity);
    setInvalidReason(reason);
    if (reason !== null) {
      return;
    }
    onSubmit({
      ownerId: selectedOwner,
      fulfillmentNodeId: selectedNode,
      externalOrderNo: externalOrderNo.trim(),
      shipToZone: shipToZone.trim(),
      shipToAddress: shipToAddress.trim(),
      promisedDeliveryDate,
      lines: [{ skuCode: selectedSku, quantity: Number(quantity) }],
    });
  }

  return (
    <form className={styles.form} onSubmit={handleSubmit} noValidate>
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
        <label className={styles.label} htmlFor={nodeId}>出貨倉</label>
        <select
          id={nodeId}
          className={styles.input}
          value={selectedNode}
          onChange={(event) => setSelectedNode(event.target.value)}
          disabled={selectedOwner === ''}
        >
          <option value="">請選擇</option>
          {nodes.map((node) => (
            <option key={node.nodeId} value={node.nodeId}>
              {node.name}（{node.code}）
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

      <div className={styles.field}>
        <label className={styles.label} htmlFor={productId}>款</label>
        <select
          id={productId}
          className={styles.input}
          value={selectedProduct}
          onChange={(event) => handleProductChange(event.target.value)}
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
        <label className={styles.label} htmlFor={skuId}>規格</label>
        <select
          id={skuId}
          className={styles.input}
          value={selectedSku}
          onChange={(event) => setSelectedSku(event.target.value)}
          disabled={selectedProduct === ''}
        >
          <option value="">請選擇</option>
          {skus.map((sku) => (
            <option key={sku.skuId} value={sku.skuCode}>
              {sku.specName}（{sku.skuCode}・{sku.weightGram}g）
            </option>
          ))}
        </select>
      </div>

      <div className={styles.field}>
        <label className={styles.label} htmlFor={quantityId}>數量</label>
        <input
          id={quantityId}
          className={`${styles.input} ${styles.quantity}`}
          value={quantity}
          onChange={(event) => setQuantity(event.target.value)}
          inputMode="numeric"
        />
      </div>

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

      <button type="submit" disabled={pending}>
        {pending ? '送出中…' : '送出訂單'}
      </button>
      {invalidReason === null ? null : (
        <p className={styles.error} role="alert">{invalidReason}</p>
      )}
    </form>
  );
}

/**
 * 送出前擋掉不完整的選擇與非正整數數量，這種輸入不發出請求——讓後端回 400 也行，但那會在
 * 操作台上製造一次沒有必要的往返，也讓「這是輸入錯誤」的回饋慢一拍。
 */
function validate(
  ownerId: string,
  nodeId: string,
  externalOrderNo: string,
  skuCode: string,
  quantity: string,
): string | null {
  if (ownerId === '') {
    return '請選擇貨主';
  }
  if (nodeId === '') {
    return '請選擇出貨倉';
  }
  if (externalOrderNo.trim() === '') {
    return '上游單號不可為空';
  }
  if (skuCode === '') {
    return '請選擇款與規格';
  }
  const parsed = Number(quantity);
  if (quantity.trim() === '' || !Number.isInteger(parsed) || parsed <= 0) {
    return '數量必須是正整數';
  }
  return null;
}

function defaultPromisedDate(): string {
  const inAWeek = new Date();
  inAWeek.setDate(inAWeek.getDate() + 7);
  return inAWeek.toISOString().slice(0, 10);
}
