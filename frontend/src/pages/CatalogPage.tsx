import { Select, SelectOption } from '../components/Select';
import { useMemo, useState } from 'react';

import type { CatalogSku } from '../api/catalog';
import type { ProductView } from '../api/types';
import { useCatalog } from '../hooks/useCatalog';
import styles from './CatalogPage.module.css';

interface VisibleProduct {
  product: ProductView;
  skus: readonly CatalogSku[];
}

/** 唯讀主檔瀏覽；主檔維護需要另一套授權與稽核，不在 DEMO 操作台提供。 */
export function CatalogPage() {
  const catalog = useCatalog();
  const [requestedOwnerId, setRequestedOwnerId] = useState('');
  const [keyword, setKeyword] = useState('');

  const owners = catalog.owners;
  const ownerId = owners.some((owner) => owner.ownerId === requestedOwnerId)
    ? requestedOwnerId
    : (owners[0]?.ownerId ?? '');
  const owner = owners.find((candidate) => candidate.ownerId === ownerId);
  const facilities = catalog.facilitiesOf(ownerId);
  const visibleProducts = useMemo(
    () => filterProducts(catalog.productsOf(ownerId), catalog, ownerId, keyword),
    [catalog, ownerId, keyword],
  );

  return (
    <div className={styles.page}>
      <header className={styles.intro}>
        <div>
          <p className={styles.eyebrow}>Reference data</p>
          <h2 className={styles.title}>主檔瀏覽</h2>
          <p className={styles.description}>
            查看貨主、可出貨倉庫、商品與 SKU。此頁只有讀取能力，不提供主檔異動。
          </p>
        </div>
        <span className={styles.readonly}>唯讀</span>
      </header>

      <section className={styles.filters} aria-label="主檔篩選">
        <label>
          貨主
          <Select
            value={ownerId}
            onValueChange={value => setRequestedOwnerId(value)}
            disabled={owners.length === 0}
          >
            {owners.length === 0 && <SelectOption value="">載入中…</SelectOption>}
            {owners.map((candidate) => (
              <SelectOption key={candidate.ownerId} value={candidate.ownerId}>
                {candidate.name} · {candidate.code}
              </SelectOption>
            ))}
          </Select>
        </label>
        <label>
          篩選商品 / SKU
          <input
            type="search"
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
            placeholder="輸入名稱、代碼或規格"
          />
        </label>
      </section>

      {owner === undefined ? (
        <p className={styles.empty}>主檔載入中，或目前沒有任何貨主。</p>
      ) : (
        <>
          <section className={styles.ownerCard} aria-labelledby="selected-owner">
            <div>
              <p className={styles.eyebrow}>Selected owner</p>
              <h3 id="selected-owner" className={styles.ownerName}>{owner.name}</h3>
            </div>
            <Definition label="代碼" value={owner.code} />
            <Definition label="Owner ID" value={owner.ownerId} mono />
            <Definition label="可出貨倉" value={`${facilities.length} 座`} />
            <Definition label="商品" value={`${catalog.productsOf(ownerId).length} 款`} />
          </section>

          <div className={styles.columns}>
            <section className={styles.panel} aria-labelledby="facilities-heading">
              <div className={styles.panelHeading}>
                <div>
                  <p className={styles.eyebrow}>Fulfillment nodes</p>
                  <h3 id="facilities-heading">可出貨倉庫</h3>
                </div>
                <span>{facilities.length}</span>
              </div>
              {facilities.length === 0 ? (
                <p className={styles.empty}>此貨主尚未指派出貨倉。</p>
              ) : (
                <div className={styles.facilityList}>
                  {facilities.map((facility) => (
                    <article key={facility.facilityId} className={styles.facilityCard}>
                      <div className={styles.cardTitle}>
                        <strong>{facility.name}</strong>
                        <code>{facility.code}</code>
                      </div>
                      <p className={styles.identifier}>{facility.facilityId}</p>
                      <ul className={styles.locationList}>
                        {catalog.locationsOf(facility.facilityId).map((location) => (
                          <li key={location.locationId}>
                            <span>{location.name}</span>
                            <code>{location.code}</code>
                            <small>{location.locationId}</small>
                          </li>
                        ))}
                      </ul>
                    </article>
                  ))}
                </div>
              )}
            </section>

            <section className={styles.panel} aria-labelledby="products-heading">
              <div className={styles.panelHeading}>
                <div>
                  <p className={styles.eyebrow}>Product catalog</p>
                  <h3 id="products-heading">商品與 SKU</h3>
                </div>
                <span>{visibleProducts.length}</span>
              </div>
              {visibleProducts.length === 0 ? (
                <p className={styles.empty}>沒有符合篩選條件的商品或 SKU。</p>
              ) : (
                <div className={styles.productList}>
                  {visibleProducts.map(({ product, skus }) => (
                    <article key={product.productId} className={styles.productCard}>
                      <div className={styles.productHeader}>
                        <div>
                          <strong>{product.name}</strong>
                          <code>{product.productCode}</code>
                        </div>
                        <span className={styles.temperature}>{temperatureLabel(product.temperatureZone)}</span>
                      </div>
                      <p className={styles.identifier}>{product.productId}</p>
                      <table>
                        <thead>
                          <tr>
                            <th>SKU</th>
                            <th>規格</th>
                            <th>重量</th>
                            <th>SKU ID</th>
                          </tr>
                        </thead>
                        <tbody>
                          {skus.map((sku) => (
                            <tr key={sku.skuId}>
                              <td><code>{sku.skuCode}</code></td>
                              <td>{sku.specName}</td>
                              <td>{sku.weightGram.toLocaleString()} g</td>
                              <td className={styles.identifier}>{sku.skuId}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </article>
                  ))}
                </div>
              )}
            </section>
          </div>
        </>
      )}
    </div>
  );
}

function Definition({ label, value, mono = false }: { label: string; value: string; mono?: boolean }) {
  return (
    <dl className={styles.definition}>
      <dt>{label}</dt>
      <dd className={mono ? styles.identifier : undefined}>{value}</dd>
    </dl>
  );
}

function filterProducts(
  products: readonly ProductView[],
  catalog: ReturnType<typeof useCatalog>,
  ownerId: string,
  keyword: string,
): VisibleProduct[] {
  const normalized = keyword.trim().toLocaleLowerCase('zh-TW');
  return products.flatMap((product) => {
    const skus = catalog.skusOf(ownerId, product.productCode);
    if (normalized === '') {
      return [{ product, skus }];
    }
    const productMatches = includesKeyword(
      [product.name, product.productCode, product.temperatureZone],
      normalized,
    );
    const matchingSkus = skus.filter((sku) =>
      includesKeyword([sku.skuCode, sku.specName, String(sku.weightGram)], normalized),
    );
    if (!productMatches && matchingSkus.length === 0) {
      return [];
    }
    return [{ product, skus: productMatches ? skus : matchingSkus }];
  });
}

function includesKeyword(values: readonly string[], keyword: string) {
  return values.some((value) => value.toLocaleLowerCase('zh-TW').includes(keyword));
}

function temperatureLabel(zone: ProductView['temperatureZone']) {
  return { AMBIENT: '常溫', CHILLED: '冷藏', FROZEN: '冷凍' }[zone];
}
