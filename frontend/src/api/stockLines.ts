import type { Catalog, CatalogSku } from './catalog';
import type { StockBatchView, StockPoolView } from './types';

/**
 * 庫存頁的一列：一個規格在這個倉的全部庫存。
 *
 * `sku` 在主檔查得到時是完整的規格（品名、規格名、重量），查不到時只有代碼——壓測用的
 * `HOT-SKU` 之類的貨有庫存卻沒有主檔，那種列仍然要出現，見 {@link warehouseStockLines}。
 */
export interface StockLine {
  skuCode: string;
  /** 主檔查不到時為 `undefined`，由畫面決定怎麼退——不該讓整列變空白。 */
  sku: CatalogSku | undefined;
  /** **含過期的批。** 倉庫裡真的有這麼多，那是物理事實。 */
  onHandQuantity: number;
  reservedQuantity: number;
  /** **不含過期的批。** 配貨的取批查詢是 `效期 >= 今天`，過期的批永遠配不到。 */
  availableToPromise: number;
  /** 過期批的在手加總。它就是上面三個對不起來的差額。 */
  expiredQuantity: number;
  /** 依配貨會取用的順序（效期、入庫日）。這個倉沒有這個規格時是空陣列。 */
  batches: readonly StockBatchView[];
}

/**
 * 把後端回的分組與主檔併成畫面要的列表。
 *
 * **這個 join 在前端做，不在後端。** 「這個貨主有哪些規格」那份名單只存在主檔裡，而庫存那一
 * 側對主檔零依賴——連外鍵都沒有。要後端補齊就得讓 allocation 開始讀 catalog；而前端為了把
 * 代碼還原成看得懂的字，本來就已經把整份主檔載進來了，兩半都在它手上。
 *
 * **列表是主檔與庫存的聯集，不是只有其中一邊：**
 *
 * - 主檔有、這個倉沒有 → 四個數字都是 0、批是空的。那些零就是「這個倉缺什麼」，而補貨鍵
 *   因此到得了每一個規格。只列有貨的會讓這個倉從未放過的貨品再也進不去。
 * - 這個倉有、主檔沒有 → 仍然列出來，只是沒有品名。漏掉它等於畫面上少報了倉庫裡真實存在的
 *   貨；`Catalog.skuCodesOf` 的註解已經記著這種貨確實存在（壓測的 `HOT-SKU`）。
 *
 * 順序是**款 → 規格**，照主檔，且**不隨數量變動**。補貨的結果要手動重查才看得到，排序一旦
 * 跟著數量走，你補的那一列就會跳走——而重查的整個目的就是看它變了什麼。主檔沒有的那幾列
 * 排在最後並依代碼排序：它們沒有款可以歸。
 */
export function warehouseStockLines(
  catalog: Catalog,
  ownerId: string,
  stock: StockPoolView,
): StockLine[] {
  const batchesBySku = new Map(stock.skus.map((entry) => [entry.sku, entry.batches]));
  const lines: StockLine[] = [];

  for (const product of catalog.productsOf(ownerId)) {
    for (const sku of catalog.skusOf(ownerId, product.productCode)) {
      lines.push(lineOf(sku.skuCode, sku, batchesBySku.get(sku.skuCode) ?? []));
      batchesBySku.delete(sku.skuCode);
    }
  }

  // 剩下的是有庫存但主檔查不到的。依代碼排序，好讓重查前後位置一樣。
  for (const skuCode of [...batchesBySku.keys()].sort()) {
    lines.push(lineOf(skuCode, undefined, batchesBySku.get(skuCode) ?? []));
  }

  return lines;
}

function lineOf(
  skuCode: string,
  sku: CatalogSku | undefined,
  batches: readonly StockBatchView[],
): StockLine {
  return {
    skuCode,
    sku,
    onHandQuantity: sum(batches, (batch) => batch.onHandQuantity),
    reservedQuantity: sum(batches, (batch) => batch.reservedQuantity),
    // 過期的批不計入可承諾——配貨永遠不會取用它們，把它們算進去的話畫面會承諾一個配貨
    // 兌現不了的量，而「有貨卻配不到」那一刻會看起來像配貨壞了。
    availableToPromise: sum(
      batches.filter((batch) => !batch.expired),
      (batch) => batch.availableToPromise,
    ),
    expiredQuantity: sum(
      batches.filter((batch) => batch.expired),
      (batch) => batch.onHandQuantity,
    ),
    batches,
  };
}

function sum(batches: readonly StockBatchView[], of: (batch: StockBatchView) => number): number {
  return batches.reduce((total, batch) => total + of(batch), 0);
}
