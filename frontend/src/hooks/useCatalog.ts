import { useEffect, useState } from 'react';

import { useOwnerSession } from '../owner/OwnerSession';
import { Catalog, type CatalogEntry } from '../api/catalog';
import {
  listFacilities,
  listOwners,
  listProducts,
  listSkus,
  listStockLocations,
} from '../api/client';

/**
 * 載入目前貨主的倉別、庫位、商品與 SKU；貨主清單由頁首 session 共用。
 * 同一層查詢並行，切換貨主或離頁後不採用舊回應。
 * 元件獨立使用、沒有 session 時，仍可載入完整 Catalog。
 */
export function useCatalogState() {
  const session = useOwnerSession();
  const selectedOwner = session?.owner;
  const [catalog, setCatalog] = useState(() => new Catalog([]));
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    setCatalog(new Catalog([]));

    async function load() {
      const owners = selectedOwner ? [selectedOwner] : await listOwners();
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
  }, [selectedOwner]);

  return { catalog, loading, error };
}

export function useCatalog(): Catalog {
  return useCatalogState().catalog;
}
