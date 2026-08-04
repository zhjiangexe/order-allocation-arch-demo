import type { FacilityView, OwnerView, ProductView, SkuView } from './types';

/** 主檔查到的規格，附上所屬款的品名——列表要顯示「品名 · 規格」，而品名在款那一層。 */
export interface CatalogSku extends SkuView {
  productName: string;
}

/** 一個貨主的整份主檔。`skus` 是該貨主全部款底下的規格，不分款攤平。 */
export interface CatalogEntry {
  owner: OwnerView;
  /** 這個貨主能指定的出貨倉。空陣列代表它一個倉都沒掛，那種貨主下不了單。 */
  facilities: readonly FacilityView[];
  products: readonly ProductView[];
  skus: readonly CatalogSku[];
}

/**
 * 操作台的主檔快照：貨主、款、規格三層，進場載入一次，全畫面共用。
 *
 * <p>這是「訂單契約不帶貨主名稱與品名」那個決定的另一半。列表要把 `ownerId` 與 SKU 代碼還原
 * 成看得懂的字，本來就得有一份主檔；既然非有不可，下單表單的三層下拉也讀同一份，不再自己
 * 去抓一次——那會抓到剛剛才抓過的同一批資料。
 *
 * <p>查規格只開放 {@link findSku} 一個入口，且它一定要帶貨主。在 3PL 裡 SKU 代碼由貨主自訂、
 * 跨貨主撞號，少帶貨主的查法不會報錯，只會安靜地回別的貨主的商品；把貨主放進簽章，那種查法
 * 在型別上就寫不出來。內部以貨主、代碼兩層索引而不是把兩者串成字串鍵——沒有分隔字元，也就
 * 沒有分隔字元出現在代碼裡時會撞鍵的問題。
 */
export class Catalog {
  private readonly entries = new Map<string, CatalogEntry>();
  private readonly skuByOwnerAndCode = new Map<string, Map<string, CatalogSku>>();

  constructor(entries: readonly CatalogEntry[]) {
    for (const entry of entries) {
      this.entries.set(entry.owner.ownerId, entry);
      this.skuByOwnerAndCode.set(
        entry.owner.ownerId,
        new Map(entry.skus.map((sku) => [sku.skuCode, sku])),
      );
    }
  }

  get owners(): OwnerView[] {
    return [...this.entries.values()].map((entry) => entry.owner);
  }

  facilitiesOf(ownerId: string): readonly FacilityView[] {
    return this.entries.get(ownerId)?.facilities ?? [];
  }

  productsOf(ownerId: string): readonly ProductView[] {
    return this.entries.get(ownerId)?.products ?? [];
  }

  skusOf(ownerId: string, productCode: string): readonly CatalogSku[] {
    return (this.entries.get(ownerId)?.skus ?? []).filter(
      (sku) => sku.productCode === productCode,
    );
  }

  /** 查不到回 `undefined`，由呼叫端決定怎麼退——主檔缺一筆不該讓整個畫面變空白。 */
  findSku(ownerId: string, skuCode: string): CatalogSku | undefined {
    return this.skuByOwnerAndCode.get(ownerId)?.get(skuCode);
  }

  /** 查不到回 `undefined`，用途是把批次列表裡的 `facilityId` 換成看得懂的倉名。 */
  findFacility(ownerId: string, facilityId: string): FacilityView | undefined {
    return this.facilitiesOf(ownerId).find((facility) => facility.facilityId === facilityId);
  }

  /**
   * 某個貨主的 SKU 代碼。
   *
   * <p><b>曾經是全貨主去重的一份清單</b>，理由是「庫存池還沒有貨主維度，兩個貨主的同碼 SKU
   * 共用同一列」。庫存分貨主之後那個理由不再成立——同碼 SKU 現在是兩批不同的貨，混在一起
   * 建議會讓人查到不屬於所選貨主的代碼。
   *
   * <p>這份清單是**建議**不是限制——壓測用的 `HOT-SKU` 之類的 SKU 有庫存卻沒有主檔，改成
   * 只能從清單選就會讓它查不到。
   */
  skuCodesOf(ownerId: string): string[] {
    const codes = new Set<string>();
    for (const sku of this.entries.get(ownerId)?.skus ?? []) {
      codes.add(sku.skuCode);
    }
    return [...codes].sort();
  }
}
