# Context API／Server 邊界與履約取消協調 — Tasks

- 日期：2026-09-20
- 狀態：T1～T4 已完成；獨立微服務部署屬後續階段
- 設計與範圍：[plan.md](plan.md)

任務依序執行；每批完成後呈現 diff 與驗證結果。此文件不授權自動 commit，
實作與提交分開處理。

## T1：建立依賴與行為基線

- [x] 盤點三個 context 被 `fulfillment`、monolith、Temporal runtime、測試
  及其他 Gradle project 引用的 production type 與 test fixtures。
- [x] 固定 Events／Temporal 取消的既有結果矩陣：無 shipment、有 shipment、
  多 shipment、已取消、已完成、拒絕、重複 request 與衝突。
- [x] 檢查各 context 的架構測試、component scan 與 `@Transactional` 邊界。

驗收：列出將移入 API 的最小契約，以及必須留在 server 或組裝層的型別；
後續重構有可對照的既有測試。

## T2：定義 Yudao 式 RPC 契約與 server endpoint

- [x] 定義 Ordering RPC 的最小 request／response DTO 與 Spring HTTP Service
  mapping，不暴露 `Order` aggregate。
- [x] 定義 WMS 面向訂單的取消 RPC 與結果，將 shipment 查找及取消決策留在 WMS。
- [x] 在 Ordering／WMS server 實作內部 HTTP endpoint，委派既有 Usecase；
  保留 request ID、reason、時間及衝突語意；區分內部 RPC 與對外 REST 路徑。
- [x] 調整 Events／Temporal coordinator 使用 RPC 介面，移除其內部型別 import。
- [x] 單體只注入 server 的本機實作，不註冊相同介面的 HTTP proxy。
- [x] 補足契約與 coordinator 回歸測試，特別核對事件發布仍由 owning context 的
  Usecase 經既有 Publisher port／Integration Event Adapter 執行。

驗收：兩種取消模式結果不變，`fulfillment` 的取消程式不再依賴
Ordering／WMS Domain 或 Usecase；本機與 HTTP endpoint 共享相同契約。

## T3：拆分 Gradle API／Server project

- [x] 依 T1 盤點建立 Ordering、WMS、Inventory 各自的 `*-api`／`*-server` project；
  遷移既有 production code，保留 package 名稱與測試可執行性。
- [x] 設定 `server → api`，`fulfillment → api`，
  `deployments:monolith → server` 的 Gradle 依賴。
- [x] 配置與現有 Spring Boot 版本相容的 Spring Web HTTP Service Client 依賴，
  明確限制 HTTP client 的啟用範圍；避免單體產生重複 bean。
- [x] 處理 `TemporalFulfillmentMessagingConfiguration` 對 Inventory／WMS
  entrypoint subscription 常數的依賴，不將 entrypoint type 塞進 API。
- [x] 更新 monolith 啟動、test fixtures、Spring scan、Gradle 設定與相關文件；
  移除舊 `*-context` project 引用。
- [x] 更新 Ordering／Inventory 的 `AGENTS.md` 適用路徑，確認既有架構測試通過；
  保持原有 layer 與 Integration Event 約束，新增 `fulfillment` 對
  context 內部型別的依賴檢查。

驗收：`fulfillment` production compile classpath 只含所需 API，
monolith 可組裝所有 server，沒有 API → server 循環依賴；HTTP proxy 不在單體被意外啟用。

## T4：整合驗證與交付

- [x] 執行 `cd backend && ./gradlew spotlessApply` 與相關模組測試、
  monolith 測試；依 T1 基線驗證 Events／Temporal 取消和正常履約。
- [x] 驗證 server HTTP endpoint 契約，以及獨立 caller 設定能建立對應 Spring HTTP
  proxy；不以單體內部直接呼叫冒充遠端連線測試。
- [x] 執行 `cd backend && ./gradlew spotlessCheck`，檢查 `git diff`、
  Gradle dependency graph 與剩餘跨 context import。
- [x] 記錄保留的單體交易／併發限制，以及正式啟用遠端部署前需完成的
  逾時、重試、冪等與結果查詢設計。

驗收：測試通過、模組依賴符合 plan、變更可分批 review；不自動 commit。
