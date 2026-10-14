# Deployment support

Monolith 與 microservice 共用的技術設定。
各 deployment 決定要載入的設定；此模組不依賴 Ordering、Inventory、WMS、Fulfillment 或 Logistics Data 實作。

| 設定 | 責任 |
| --- | --- |
| `BusinessClockConfiguration` | UTC clock 與 business timezone |
| `IntegrationEventContractConfiguration` | 共用 Integration Event wire type mapping |
| `KafkaConsumerConfiguration` | failure classification 與 broker redelivery policy |
| `OptimisticLockingConfiguration` | 消費事件時，重試完整的本地交易 |

Inventory 的 allocation retry metrics observer 由 monolith 與 inventory-service 各自組裝。
共用設定不建立 context 專屬 bean；Web、JPA 與 messaging starters 由 deployment 選擇。

## Database migrations

SQL 位於 repository root 的 [`database`](../../../database/README.md)，由獨立步驟執行。
本模組不攜帶 SQL 或 Flyway runtime；monolith 與 microservice 都需先完成 schema migration 才啟動。

## 驗證範圍

修改共用設定後，執行本模組與 monolith unit tests，以及 microservice 的獨立啟動組裝測試。
修改 SQL 或跨 context 契約時，還需執行相關資料庫／事件流程測試並檢查新舊服務相容性。
