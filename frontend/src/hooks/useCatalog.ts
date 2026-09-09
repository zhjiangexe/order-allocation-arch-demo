import { useEffect, useState } from 'react';

import { Catalog, type CatalogEntry } from '../api/catalog';
import {
  listFacilities,
  listOwners,
  listProducts,
  listSkus,
  listStockLocations,
} from '../api/client';

/**
 * 進場載入一次主檔（貨主、倉庫、款、規格），供下拉選單、訂單列表的名稱解析、庫存頁的
 * SKU 建議共用。
 *
 * <p>整個畫面查一次，不是每一列、每一次選擇各查一次——後者才是 N+1。
 *
 * <p>每一層內部並行（`Promise.all`），因此請求數雖與貨主／款數成正比，往返只有三層。demo
 * 的資料量下這是可接受的；主檔若成長到這個 fan-out 會痛，就該由後端提供一支扁平的主檔查詢，
 * 而不是在前端拼（取捨見 frontend/README.md 的「主檔載入的代價」）。
 */
export function useCatalogState() {
  const [catalog, setCatalog] = useState(() => new Catalog([]));
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    async function load() {
      const owners = await listOwners();
      const entries: CatalogEntry[] = await Promise.all(
        owners.map(async (owner) => {
          const [facilities, products] = await Promise.all([
            listFacilities(owner.ownerId),
            listProducts(owner.ownerId),
          ]);
          const locations = (await Promise.all(
            facilities.map((facility) => listStockLocations(facility.facilityId)),
          )).flat();
          const perProduct = await Promise.all(
            products.map(async (product) => {
              const skus = await listSkus(owner.ownerId, product.productCode);
              return skus.map((sku) => ({ ...sku, productName: product.name }));
            }),
          );
          return { owner, facilities, locations, products, skus: perProduct.flat() };
        }),
      );

      if (!cancelled) {
        setCatalog(new Catalog(entries));
      }
    }

    void load().catch((cause: unknown) => {
      if (!cancelled) setError(cause instanceof Error ? cause.message : String(cause));
    }).finally(() => {
      if (!cancelled) setLoading(false);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  return { catalog, loading, error };
}

export function useCatalog(): Catalog {
  return useCatalogState().catalog;
}
