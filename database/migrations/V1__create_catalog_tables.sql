-- 貨主、商品、履約設施與庫位主檔；複合外鍵維持貨主與 SKU 的參照完整性。

CREATE TABLE owners (
    id UUID NOT NULL,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    CONSTRAINT owners_pkey PRIMARY KEY (id),
    CONSTRAINT uq_owners_code UNIQUE (code)
);

CREATE TABLE products (
    id UUID NOT NULL,
    owner_id UUID NOT NULL,
    product_code VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    temperature_zone VARCHAR(32) NOT NULL,
    CONSTRAINT ck_products_temperature_zone CHECK (temperature_zone IN ('AMBIENT', 'CHILLED', 'FROZEN')),
    CONSTRAINT products_pkey PRIMARY KEY (id),
    CONSTRAINT uq_products_owner_code UNIQUE (owner_id, product_code),
    CONSTRAINT fk_products_owner FOREIGN KEY (owner_id) REFERENCES owners(id)
);

CREATE TABLE skus (
    id UUID NOT NULL,
    owner_id UUID NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    product_code VARCHAR(64) NOT NULL,
    spec_name VARCHAR(255) NOT NULL,
    weight_gram INTEGER NOT NULL,
    CONSTRAINT ck_skus_weight_positive CHECK (weight_gram > 0),
    CONSTRAINT skus_pkey PRIMARY KEY (id),
    CONSTRAINT uq_skus_owner_code UNIQUE (owner_id, sku_code),
    CONSTRAINT fk_skus_product FOREIGN KEY (owner_id, product_code) REFERENCES products(owner_id, product_code)
);

CREATE INDEX idx_skus_product
    ON skus (owner_id, product_code);

CREATE TABLE facilities (
    id UUID NOT NULL,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    CONSTRAINT facilities_pkey PRIMARY KEY (id),
    CONSTRAINT uq_facilities_code UNIQUE (code)
);

CREATE TABLE stock_locations (
    id UUID NOT NULL,
    facility_id UUID,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    usage VARCHAR(32) NOT NULL,
    CONSTRAINT ck_stock_locations_facility_by_usage CHECK (
        (usage =  'INTERNAL' AND facility_id IS NOT NULL)
     OR (usage <> 'INTERNAL' AND facility_id IS NULL)
    ),
    CONSTRAINT ck_stock_locations_usage CHECK (usage IN ('INTERNAL', 'SUPPLIER', 'CUSTOMER', 'INVENTORY')),
    CONSTRAINT stock_locations_pkey PRIMARY KEY (id),
    CONSTRAINT uq_stock_locations_code UNIQUE (code),
    CONSTRAINT uq_stock_locations_id_facility UNIQUE (id, facility_id),
    CONSTRAINT uq_stock_locations_id_usage UNIQUE (id, usage),
    CONSTRAINT fk_stock_locations_facility FOREIGN KEY (facility_id) REFERENCES facilities(id)
);

CREATE TABLE owner_facilities (
    owner_id UUID NOT NULL,
    facility_id UUID NOT NULL,
    CONSTRAINT owner_facilities_pkey PRIMARY KEY (owner_id, facility_id),
    CONSTRAINT fk_owner_facilities_facility FOREIGN KEY (facility_id) REFERENCES facilities(id),
    CONSTRAINT fk_owner_facilities_owner FOREIGN KEY (owner_id) REFERENCES owners(id)
);
