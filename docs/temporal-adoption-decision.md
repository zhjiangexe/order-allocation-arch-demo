# Temporal 採用決策（歷史版本）

> 狀態：已被後續的限縮導入決策取代（2026-08-15）
> 日期：2026-08-07
> 範圍：WMS 與未來跨系統 fulfillment orchestration

現行決策見 [`order-fulfillment-temporal-hybrid-architecture.md`](order-fulfillment-temporal-hybrid-architecture.md)：
專案保留 Kafka-only profile，同時以 `fulfillment-workflow-runtime` 內的 order-level workflow 比較一條粗粒度、跨
Order Promising／WMS／Stock 的 Temporal orchestration。Temporal 不接管 Pick／Pack／Stage
等 WMS 內部流程，而且同一環境只能啟用一個 command driver。

以下內容保留為當時移除第一版 WMS workflow prototype 的歷史理由，不再表示目前完全不導入
Temporal。

## 原始決策

目前不在專案導入 Temporal，並移除 `fulfillment-workflow`、`fulfillment-workflow-wms-adapter`、`fulfillment-workflow-runtime` prototype modules。

現階段 fulfillment 業務只保留 `wms` module。另有 `foundation`、`integration-contracts`、`messaging:*` 與 `platform-infrastructure` 提供共用技術與整合契約，但它們不是 workflow／orchestration modules。Pick、Pack、Stage、Loading、Cancellation／Putback 等倉內行為，由 WMS application use case、domain model、process state 與 committed domain event 表達；跨 bounded context 的通知沿用 Outbox／Kafka。高頻掃碼、task、排程與設備控制不放入外部 workflow engine。

## 理由

- 現行主要流程仍由同一個 WMS 擁有，尚未出現必須由獨立 orchestrator 長時間等待的第二個系統。
- Pick／Pack／Stage 是 WMS business process，不會因為有先後順序就自然成為 Temporal Workflow 或 Activity。
- prototype 把尚未穩定的業務 checkpoint 提前固化成 Workflow／Signal／Activity contract，增加重複狀態、轉接 module 與 runtime 維護成本。
- 目前的 Kafka、Scheduler、Inbox／Outbox 與 domain idempotency 已能支援既有非同步流程；同時加入 Temporal 會產生多個 orchestration driver。

## 保留的設計方向

- `wms` 不依賴任何 workflow engine。
- WMS 對外只發布粗粒度、已提交的 business facts，例如 dispatch readiness、custody handover 或 cancellation outcome；不暴露每筆 scan／PickTask。
- WMS 內先依 business identity 切出 Picking、Packing、Dispatch Preparation、Loading／Handover、Recovery 等 process；不是全部塞入單一 `Shipment` 狀態機，也不預先拆成 microservices。
- 未來若導入 orchestration module，應依真正的跨系統流程命名，例如 `outbound-dispatch-workflow`，避免使用過度泛化的 `fulfillment-workflow`。

## 重新評估 Temporal 的門檻

至少同時具備下列大部分條件，才新增 workflow contract、adapter 與 runtime：

1. 有兩個以上可獨立失敗、部署與演進的系統或 bounded contexts。
2. 有穩定且唯一的 workflow business key。
3. 需要跨 transaction 等待分鐘、數小時或數天。
4. 有明確 timeout、retry、cancellation、compensation 或人工介入政策。
5. 能指定單一 orchestration driver，避免與 Kafka consumer／Scheduler 重複驅動同一副作用。
6. Activity 可重入，且提交後的結果能 durable replay。
7. Workflow 只接收粗粒度 business facts，不會被高頻 WMS task／設備訊號灌滿 history。

只符合「步驟很多」或「程式碼看起來像流程」，不足以導入 Temporal。

## 影響

- 刪除現有 Temporal prototype 不影響 `wms` domain／application code。
- WMS 邊界與流程分解文件繼續保留，作為業務建模依據，而非現行 Temporal implementation 說明。
- 未來條件成立時，從已穩定的 WMS commands、events 與 business keys 建立新的 orchestration contract，不復活目前 prototype 的細粒度 Signals。
