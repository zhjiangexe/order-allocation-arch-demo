# Context API／Server 邊界與履約取消協調 — Plan

- 日期：2026-09-20
- 狀態：單體與 Spring HTTP Service Client 範例已實作並驗證；獨立微服務部署仍屬後續階段
- 任務清單：[tasks.md](tasks.md)

> 本文件保留當時的 RPC 拆分規劃與驗收紀錄。後續 Events 取消改由 process manager
> 編排，WMS 取消 RPC 與 Ordering `commit` RPC 已移除；目前 Ordering API 僅提供
> `assess`。現行取消流程見 [fulfillment-cancellation-process-manager.md](../../fulfillment-cancellation-process-manager.md)。

## 目標

將 `ordering-context`、`inventory-context`、`wms-context` 的公開 RPC 契約與實作分開，
示範 Yudao Cloud 式 `api/server` 邊界，並讓未來的微服務部署能沿用同一份契約。
`fulfillment` 只依賴必要的 `*-api`；`deployments:monolith` 統一組裝 server
實作。保留 Events 與 Temporal 兩種模式作為範例，不以其中一種取代另一種。

這次以 Spring HTTP Service Clients 與 `RestClient` 作為 RPC client 範例，`*-api`
定義帶 `@PostExchange` 的介面及傳輸 DTO，`*-server` 實作對應的內部 HTTP
endpoint。單體模式直接注入 server 的本機實作；未來微服務模式可透過
`CancellationHttpClientConfiguration` 建立 HTTP proxy。這裡的「未來可沿用」
指契約與程式碼接線，不宣稱本階段已完成獨立部署。
本階段的模組拆分原先維持既有取消語意；後續 Events 模式改為由事件推進。

## 現況與需要切開的依賴

- `fulfillment` 目前直接依賴三個完整 `*-context` Gradle project。
- Events 取消 coordinator 直接使用 Ordering／WMS Usecase、`Order` aggregate、
  `OrderStatus`、`ShipmentView` 與 `CancelShipmentStatus`；它同時承擔跨 context
  流程與部分 context 內部判斷。
- Temporal 取消 coordinator 也直接讀取 Ordering Usecase 與 domain type。
- `TemporalFulfillmentMessagingConfiguration` 引用 Inventory／WMS entrypoint 的
  subscription 常數。即使取消 coordinator 改完，這些引用仍會阻礙移除完整 context 依賴。
- Monolith 直接組裝三個 context，並使用 Inventory test fixtures；拆分時須保留啟動與測試接線。
- Ordering、Inventory 各有 `AGENTS.md` 與架構測試；移動 production type 時須同步檢查規則，
  不以排除或放寬測試取代邊界設計。

## 目標依賴方向

```text
fulfillment ──> ordering-api
                       (僅實際使用的 RPC 契約)
ordering-server ──────> ordering-api
inventory-server ─────> inventory-api
wms-server              (取消入口改由 Integration Event／Temporal Activity 提供)
deployments:monolith ─> fulfillment + 三個 *-server
```

Gradle project 名稱以 `:ordering:ordering-api`、`:ordering:ordering-server` 等為目標；
採用現有 package 名稱，避免為了 Gradle 改名而大規模搬動 Java namespace。
`*-api` 僅收跨模組需要的 RPC 介面、request／response DTO 與穩定識別資料；
可包含 Spring `@PostExchange` mapping，不放 Aggregate、Repository、JPA entity
或 Usecase 實作。`*-server` 提供 `@RestController` 實作，委派 context 內部
Application／Domain／Infrastructure。內部 RPC 路徑須與面向使用者的 REST 路徑區分。
Gradle 只需配置 Spring Web；HTTP client proxy 僅在遠端部署模式註冊。
事件的 versioned contract 繼續由 `integration-contracts` 管理，遵守既有
Application Event → Publisher port → Integration Event Adapter 規則。

## 取消流程的邊界

- Ordering RPC 提供取消前所需的最小狀態／衝突判斷與取消命令結果；
  `fulfillment` 不讀取 `Order` aggregate 或直接引用 `*Usecase`。
- WMS RPC 提供面向訂單的取消操作及明確結果；實作仍由 WMS 自己處理 shipment
  查找、數量衝突及取消。不要將完整 `ShipmentView` 暴露給流程模組，
  也不要只把目前兩次內部呼叫原樣包成兩個公開 HTTP 式方法。
- 必須核對「查無 shipment 後取消 Order」與同時建立 shipment 的競態。
  若此次只維持既有單體語意，記錄其限制；不得宣稱抽出介面已保證未來跨服務原子性。
- Events 模式受理命令後以 Integration Event 推進 WMS／Ordering；
  Temporal 模式維持 Workflow 入口。兩種模式都透過公開業務契約取得所需資料。
- 單體的本機呼叫與未來 HTTP 呼叫具有不同交易語意。`@Transactional` 不會
  跨 HTTP 保證原子性；在實際啟用微服務部署前，須另行設計各服務本地交易、
  逾時、重試、冪等與結果查詢。本階段不假設遠端呼叫能共用交易。

## 模組與相容性策略

先盤點三個 context 的所有跨模組 Java import、Gradle 依賴與 Spring component scan。
只有真正被外部呼叫的型別進入 `*-api`；Inventory 若目前只需要共享 subscription
識別，不因此建立假的 Inventory 業務操作。此類配置常數應移至其真正的組裝 owner，
或定義適當的 shared messaging contract，避免 `fulfillment` import entrypoint。

單體啟動時只註冊 server 的本機實作；微服務 caller 啟動時只註冊該介面的
HTTP proxy，避免同一介面出現兩個候選 bean 或錯誤地在單體內走 HTTP。
以 `archone.fulfillment.rpc.transport=http` 的部署設定及接線測試驗證這個差別。

先建立 API、server endpoint 與本機接線並完成呼叫者遷移，再移除舊 `*-context` project；
每一步保持可編譯。若一次搬移三個大型 context 造成無關變更，按 Ordering、WMS、
Inventory 分批完成，但最終 Gradle 圖必須符合上述方向。不要建立僅為轉發而無業務語意的介面。

## 驗收

- `fulfillment` production compile classpath 不含三個 `*-server`，且 source
  不再 import Ordering／Inventory／WMS 的 Usecase、Domain、Entrypoint、Infrastructure。
- Monolith 可在 Events 與 Temporal 設定下啟動；既有取消、履約與事件路徑行為維持。
- 對同一 RPC 介面，單體只注入本機 bean；微服務 caller 的接線測試只注入
  HTTP proxy。server endpoint 的 HTTP request／response 有契約驗證。
- 取消的接受、拒絕、已取消、重複 request 與多 shipment 衝突有適當回歸驗證。
- Gradle 模組及 architecture tests 能防止 API 反向依賴 server，以及流程模組重新依賴內部實作。
- 修改 Java 後執行 `cd backend && ./gradlew spotlessApply`；完成相應 context、
  `fulfillment`、monolith 測試，提交前執行 `spotlessCheck`。

## 不在本階段

- 實際拆成獨立服務、Gateway、服務發現，以及 Retrofit client。
- 新增資料表、重寫 Integration Event 格式，或重新定義 Events／Temporal 兩種範例的產品語意。
- 為每個 context 預先設計完整對外 API；只抽取目前需要的契約。

## 實作與驗證紀錄（2026-09-20）

- 三個 context 已搬到 `ordering/`、`inventory/`、`wms/` 下的 `*-api`／
  `*-server` Gradle project。Inventory 目前無同步 RPC 呼叫，`inventory-api`
  刻意沒有 Java 契約；取消後的庫存處理仍由既有 Integration Event 推進。
- Ordering、WMS 取消 RPC 介面位於各自 API；server 實作內部 HTTP endpoint，
  並在單體內提供同一介面的本機 bean。`fulfillment` 已移除對三個
  完整 context project 的依賴及內部 Usecase／Domain／Entrypoint import。
- Ordering／WMS API 各以獨立 Spring context 建立 HTTP proxy 並呼叫測試 HTTP
  server；monolith 接線測試確認只取得本機實作。這驗證了契約可作遠端 client，
  不等於已完成正式微服務部署。
- `./gradlew test spotlessCheck` 通過；`make e2e` 的 Events 與 Temporal
  完整案例通過。首次 E2E 被既有 catalog/idempotency feature 的兩個過時
  409 字串斷言阻擋，已改為核對目前 Problem Details 的 `code`，重跑全套通過。
- 保留既有「WMS 查無 shipment → Ordering 取消 Order」路徑；在真正拆成服務前，
  仍需解決與同時建立 shipment 的競態、跨 HTTP 的交易切分，以及遠端錯誤／
  逾時與重試語意。此次沒有宣稱介面本身解決上述問題。

## RPC client 選擇更新（2026-09-20）

改用 Spring HTTP Service Clients／`RestClient`；本機與遠端仍共用 `*-api`
契約。遠端 Ordering client 由 `archone.fulfillment.rpc.transport=http` 與 Ordering base URL
啟用，預設 connect timeout 2 秒、read timeout 5 秒，不自動重試。HTTP 4xx／5xx
與傳輸失敗不得轉成業務 `REJECTED`；取消命令逾時可能已提交，重送須沿用
同一 request ID 與 payload。獨立微服務部署及持久化結果查詢仍屬後續階段。
取消入口目前將下游衝突映射為 409、不可用映射為 503、逾時映射為 504；
下游其他異常回應為 502。遠端錯誤目前只保留 HTTP 狀態分類，尚未傳遞
下游 ProblemDetail 的細部業務錯誤碼。`./gradlew test spotlessCheck` 與完整
`make e2e`（Events／Temporal）再次通過。

## Events 取消流程更新（2026-09-20）

Events coordinator 保留與 Temporal 相同的同步 Ordering 前置檢查，但不再同步呼叫
WMS 或執行 Ordering 取消命令。受理請求時由本地 transaction 寫入 outbox，
WMS 消費取消請求事件；Shipment 取消完成沿用既有 `ShipmentCancelledIntegrationEvent`
推進 Ordering，查無 Shipment 則以新結果事件推進 Ordering。WMS 拒絕或多 Shipment
的結果也發布為事件，但目前沒有可供外部查詢的請求狀態 projection；HTTP 202
只代表 outbox 已受理。重複 request 可能產生重複意圖事件，依 WMS／Ordering 的
request ID 冪等檢查處理；獨立的受理去重與結果查詢仍需後續設計。
