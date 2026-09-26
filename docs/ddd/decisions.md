# 架構決策與待決問題

更新日期：2026-09-26

本文件只保存仍影響未來設計的取捨。已完成的 migration、方案比較過程與逐步實作紀錄由 Git history 保存。

## 已定案

### 產品與邊界

- 目前採 warehouse-centric 3PL（A），目標為 A+C；現階段不承擔 OMS／DOM 的 sourcing 與 promise 權責。
- `backend/fulfillment` 的目標定位是 **Outbound Fulfillment Operations bounded context**，不是包辦 inbound、
  outbound、returns 的 generic Operations context。
- Control Tower 初期是 projection、時間線、告警與工作入口，不成為來源 aggregate 的第二套真相。
- Partner Integration／ACL 與 3PL Billing 維持獨立邊界。
- Returns 先作為獨立 capability；Inbound execution 先留在 WMS／Inventory。

### 受理與服務政策（Target）

1. 上游指定服務等級時優先採用；未指定時使用 Owner 的 `OperationalServiceProfile` 預設。
2. Archone 必須驗證合約授權、冷鏈、法規與 Facility capability 等硬條件。
3. 要求與硬條件不一致時進入人工確認，不自動覆寫後繼續。
4. 法規、安全、溫控或物理能力限制不可人工強制放行。
5. 待審期間只有 `OrderSubmission` 與不可變 revision：不建立正式 Order、不保留庫存、不建立 WMS 工作。
6. Owner 未回覆時持續等待；逾期只產生提醒、升級與 SLA 記錄，不自動核准或拒絕。

### Order 與 Fulfillment

- 每張正式 Order 固定一個 Facility；跨 Facility 由上游拆成多張 Order。
- 是否允許部分出貨由上游指定，未指定時依 Owner policy；Archone 不自行做商業拆單。
- Target 中每張 Order 對應一個 `FulfillmentCase`，可關聯多個 Shipment，依 required milestones 結案。
- 暫不建立 `FulfillmentUnit`。只有出現無法用 OrderLine、Shipment reference 與 milestone 表達的獨立履約分支，
  才重新命名並建模。
- SLA 分為 Intake、Fulfillment 與 Exception Resolution；同時保存 wall-clock 與 accountable time。

### Inventory

- `StockOperation` 是同一 source unit、路線與 assignment policy 下的 movement group，不是 WMS work。
- `StockMove` 是 SKU 搬運意圖；`StockMoveLine` 是 Move 對具體 Quant／批次的承諾。
- `StockQuant` 保存物化的 on-hand 與 reserved；ATP 由兩者計算，不保存第三份餘額。
- Allocation planning 可以丟棄；只有同一交易內完成的 assignment 才是 committed fact。
- 現行 assignment policy 是 `SHIP_COMPLETE`：一個 operation 的所有 moves 必須原子滿足或完全不配置。
- Owner 是庫存、ACL、政策與未來計費的強制隔離維度；物理混放是另外的政策問題。

### Integration boundaries

- Business code 以 Application Event 與 Publisher port 表達已發生事實；只有 infrastructure adapter 使用
  versioned Integration Event contract 與 `IntegrationEventPublisher`。
- Consumer 只做 contract-to-command normalization，不自行把 use case result 編排成下一個 outbound event。
- 同一完整 Application Event 可以由一個 adapter 原子轉成多個 audience-specific publications。
- Temporal Activity 是 command delivery boundary；terminal fact 仍由擁有該事實的 context 發布。
- `aggregateId` 表達 domain identity；`partitionKey` 表達 Kafka ordering／contention boundary，兩者不得混用。

## Open：尚待決定

1. **Events 與 Temporal 的長期策略**：永久雙 runtime、Temporal 最終接管，或 Events 最終保留其一。
2. **Operational Service Policy 落點**：先放 Fulfillment／Logistics Data，或建立獨立 module；profile 採 snapshot、
   version reference 或兩者並存。
3. **Control Tower 升格條件**：何時跨流程的 assignment、escalation、responsibility clock 與 resolution policy
   足以形成 `Operational Work Management` context。
4. **Inbound／Returns 升格條件**：何種業務複雜度值得建立獨立 bounded context。
5. **3PL Billing 模型**：storage、handling、VAS、minimum、tier、effective dating、tax 與 adjustment 的責任切點。
6. **組織與部署**：各 context 的 domain owner、schema owner、SLO、incident owner 與獨立部署門檻。
7. **KPI 與投資分類**：履約、庫存、例外、計費 KPI，以及 Core／Supporting／Generic 與自建／採購決策。

決定上述事項時，必須同步更新[策略](strategy.md)、[Context map](context-map.md)及對應的 executable tests；不要
新增另一套平行的規格庫。
