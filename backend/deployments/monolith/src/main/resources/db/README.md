# 資料庫初始化

此專案尚未上線，原 V1–V32 已整理為六份直接建立最終模型的 migration。
歷史版本可由 Git 查閱；不再建立已淘汰的表格或執行舊資料轉換。

| Migration | 職責 |
| --- | --- |
| V1 | 貨主、商品／SKU、設施、庫位與貨主設施關係 |
| V2 | 訂單與訂單行，包含履約與取消關聯 |
| V3 | 庫存批次、配貨政策、StockOperation／Move、取消與收貨冪等記錄 |
| V4 | Subscriber-aware Inbox 與含 headers 的 Outbox |
| V5 | WMS Shipment、波次、揀貨、交運與收貨 |
| V6 | Inventory 的 deferred constraint triggers、functions 與 Ordering 來源 View |

欄位、PRIMARY KEY、UNIQUE、CHECK 與 FOREIGN KEY 都在各自的 CREATE TABLE 內定義。
索引緊接其表格；跨表的 deferred guards 在所有表建立後定義，保留交易結束時的完整性檢查。
沒有為了整理 schema 改變既有 nullable、default、索引、外鍵刪除策略或業務約束。

## Seed

Migration 只建立 schema；空白資料庫套用原 V1–V32 後同樣沒有業務 seed rows。
舊 UPDATE／INSERT SELECT 是歷史資料轉換，不是新環境必需的示範資料，已移除。

Dev profile 的 `DevSeedDataInitializer` 直接建立現行主檔、庫存批次、訂單與 StockOperation／Move，
不依賴 allocation demand、stock picking 或 WMS 舊欄位回填。主檔先於參照它的庫存與訂單建立，
整批 seed 在同一個 transaction 內完成，讓 deferred guards 驗證最終一致性；重跑不重複新增。
`owner_allocation_policies` 是可選的貨主覆寫，未設定時使用 application 預設 `DISPATCH_DATE_FIRST`，
不需要將舊版對既有 owners 的回填改成 dev seed。

## 既有開發資料庫

本次重整不是既有 schema 的升級路徑。既有 Flyway history 與本次 V1–V6 不相容，
不能以 `flyway repair` 解決。請使用空白資料庫，或備份／確認資料可捨棄後另行重建。
簡報環境的 `make demo-down` 保留所有資料，不會自動清空舊 schema；本次程式修改也沒有刪除其資料。
未來有需要保留資料的正式環境後，新增版本 migration，不再改寫已套用的檔案。

## 驗證

整理時於獨立 PostgreSQL 16 資料庫分別套用原 V1–V32 與新版 V1–V6；
`pg_dump --schema-only --no-owner --no-privileges` 與 data-only dump 排除隨機的 restrict token 後完全一致。
比對涵蓋 27 張表、24 個額外索引（不含 PK／UNIQUE 自帶索引）、1 個 View、4 個 function 與 6 個 trigger。

若本機曾編譯舊 migration，先執行 `./gradlew :deployments:monolith:clean` 清除舊的 build resources，
避免舊 SQL 殘留在輸出目錄；此命令只清編譯產物，不會清資料庫。

從 backend 執行：

```bash
./gradlew :deployments:monolith:test :deployments:monolith:sit
```

`DatabaseFoundationIntegrationTest` 驗證新版 migration 套用、Flyway 重跑無變更與 canonical metadata。
各 schema／persistence／transaction SIT 繼續驗證最終業務約束；`DevSeedDataIntegrationTest` 驗證 seed
資料一致性與重跑。只服務歷史升級的 checksum guard 與分階段回填測試已移除。
