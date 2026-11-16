# 07｜BDD 規格案例

使用 Given（前提）、When（操作）、Then（預期結果）描述業務情境。每個情境獨立執行；「最終」表示等待非同步流程完成，「受理取消」不等於取消完成。

Feature 檔案保留業務描述與案例識別碼，供需求討論與驗收閱讀；此頁不代表案例已執行或通過。

## 1 下單前主檔與同步命令的邊界行為

[下載 Feature](biz-bdd/catalog-and-idempotency.feature)

<!-- feature: catalog-and-idempotency.feature -->

## 2 Events 模式的配貨與履約流程

[下載 Feature](biz-bdd/events-fulfillment.feature)

<!-- feature: events-fulfillment.feature -->

## 3 Events 模式的取消流程

[下載 Feature](biz-bdd/events-cancellation.feature)

<!-- feature: events-cancellation.feature -->

## 4 Events 模式在倉內作業前取消 Shipment

[下載 Feature](biz-bdd/events-shipment-cancellation.feature)

<!-- feature: events-shipment-cancellation.feature -->

## 5 Debezium connector 暫停後追趕

[下載 Feature](biz-bdd/connector-catch-up.feature)

<!-- feature: connector-catch-up.feature -->

## 6 Temporal 模式的配貨與履約流程

[下載 Feature](biz-bdd/temporal-fulfillment.feature)

<!-- feature: temporal-fulfillment.feature -->

## 7 Temporal workflow 的取消分支

[下載 Feature](biz-bdd/temporal-cancellation.feature)

<!-- feature: temporal-cancellation.feature -->
