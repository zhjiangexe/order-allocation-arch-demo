## REMOVED Requirements

### Requirement: Stock pool state is queryable by SKU
**Reason**: 這條 requirement 的形狀整個是為「一個 SKU 散在哪些倉」長的——先按倉分組再依效期排序、查無回 `404`。查詢軸換成倉之後三件事都不成立：一個倉裡沒有跨倉可分組、空倉是正常答案而不是查無、而回應要涵蓋這個倉的每一個 SKU 而不是一個。標題本身斷言了 "by SKU"，因此無法只改內容。

**Migration**: 由 **Stock held in a warehouse is queryable by owner and warehouse** 取代。保留的：每一批帶倉別、入庫日、效期、三個數量與是否過期；過期的批要在回應裡並標記；可承諾以領域語彙命名而非縮寫；排序由端點保證而不是留給呼叫端。移除的：跨倉分組（範圍已經只有一個倉）、查無回 `404`。`GET /stock-pool/{sku}` 這條路徑一併移除——它在 production 只有操作台一個呼叫端，跟著這一頁換掉。

## ADDED Requirements

### Requirement: Stock held in a warehouse is queryable by owner and warehouse

The stock query endpoint SHALL return, for a given owner and warehouse, **every batch
that owner holds there**, grouped by SKU code. Each batch SHALL carry its arrival date,
expiry date, on-hand quantity, reserved quantity, available-to-promise quantity, and
whether it has expired. The available-to-promise field SHALL be named after the domain
concept rather than an abbreviation, matching the vocabulary of the allocation domain
model.

Both an owner and a warehouse SHALL be required. A SKU code alone no longer identifies
stock — it collides across owners — and allocation never spans warehouses, so a reply
covering several would suggest a pool that no single allocation can draw on.

Expired batches SHALL be present in the response and marked, not omitted. Omitting them
makes "we hold 100 units but can ship none" indistinguishable from "we hold nothing".

**Within each SKU the batches SHALL be ordered by expiry date, then arrival date, then
identity** — exactly the order allocation would draw on them. The ordering SHALL be a
guarantee of this endpoint rather than left to callers: the tie-breaks reach down to row
identity, which exists to make the order reproducible and carries no meaning a caller
could sort on.

**An owner and warehouse holding nothing SHALL yield an empty result, not `404`.** A
warehouse holding none of an owner's goods is an ordinary answer rather than a question
about something that does not exist — and it is the state a newly opened warehouse is in,
which is precisely when someone needs to look at it.

The endpoint SHALL NOT verify that the owner is assigned to the warehouse. That
assignment lives in the catalog, and stock has no reference to it; checking it here would
make the allocation side depend on the catalog for the first time.

#### Scenario: Every SKU the owner holds in that warehouse is reported

- **GIVEN** an owner holds two SKUs in one warehouse, one of them as three batches
- **WHEN** that owner's stock in that warehouse is queried
- **THEN** the response carries both SKUs, the first with three batches and the second
  with one, each batch with its own dates and quantities

#### Scenario: Batches of one SKU are ordered as allocation would consume them

- **GIVEN** an owner holds one SKU in a warehouse as several batches of differing expiry
- **WHEN** that owner's stock in that warehouse is queried
- **THEN** within that SKU the earliest-expiring batch comes first

#### Scenario: An expired batch is returned and marked

- **GIVEN** one of the batches has passed its expiry date
- **WHEN** that owner's stock in that warehouse is queried
- **THEN** that batch is present and marked as expired

#### Scenario: A warehouse holding nothing answers with an empty result

- **GIVEN** an owner holds no stock at all in a warehouse
- **WHEN** that owner's stock in that warehouse is queried
- **THEN** the response is successful and carries no SKUs

#### Scenario: Stock in another warehouse is not reported

- **GIVEN** an owner holds the same SKU in two warehouses
- **WHEN** that owner's stock in one of them is queried
- **THEN** only that warehouse's batches are present

##### Example: what identifies a stock query

| Owner | Warehouse | Result |
| --- | --- | --- |
| given | given | that owner's batches in that warehouse, grouped by SKU |
| given | given, holds nothing | successful, empty |
| given | omitted | `400` |
| omitted | given | `400` |
