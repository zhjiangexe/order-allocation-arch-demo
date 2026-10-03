# 架構決策與待決問題

更新日期：2026-09-26

本文件只保存仍影響未來設計的取捨。已完成的 migration、方案比較過程與逐步實作紀錄由 Git history 保存。

## 狀態與維護方式

- `Current`：已由 production code／migration／automated tests 實現的決策。
- `Target`：方向已定案，但不能據此宣稱功能已存在。
- `Open`：尚未定案；在觸發條件成立前不預先建立抽象或 module。
- 「重新檢討條件」是必須重開決策的業務訊號，不是承諾日期或 roadmap。

每項決策以穩定 ID 供 issue、commit 與 review 引用。實作證據仍以程式碼及測試為準；文件只保存狀態、理由與
何時需要重新決定。

本文件是 decision register；需要保存替代方案、正負後果與驗證方式的重大決策，另建短篇
[Architecture Decision Record](../adr/README.md)，並由兩邊互相連結。

## 已定案

### DEC-001：產品與邊界

| Metadata | 內容 |
| --- | --- |
| 狀態 | `Current`：warehouse-centric 3PL；`Target`：A+C |
| 理由 | 先穩定庫存與倉內執行真相，再增加跨 context operations；不承接尚未授權的 OMS／DOM 決策 |
| 重新檢討條件 | Archone 取得選倉、跨倉拆單、交期承諾或成本最佳化的權責 |
| 主要證據 | [產品策略](strategy.md)、[Context map](context-map.md)、`backend/settings.gradle` |

- 目前採 warehouse-centric 3PL（A），目標為 A+C；現階段不承擔 OMS／DOM 的 sourcing 與 promise 權責。
- `backend/fulfillment` 的目標定位是 **Outbound Fulfillment Operations bounded context**，不是包辦 inbound、
  outbound、returns 的 generic Operations context。
- Control Tower 初期是 projection、時間線、告警與工作入口，不成為來源 aggregate 的第二套真相。
- Partner Integration／ACL 與 3PL Billing 維持獨立邊界。
- Returns 先作為獨立 capability；Inbound execution 先留在 WMS／Inventory。

### DEC-002：受理與服務政策

| Metadata | 內容 |
| --- | --- |
| 狀態 | `Target` |
| 理由 | 在建立正式 Order 與庫存承諾前，先把上游要求、Owner 預設與不可放寬的硬條件收斂成可稽核決定 |
| 重新檢討條件 | 實作 `OrderSubmission`／service profile，或外部合約系統開始提供可執行的授權決策 |
| 主要證據 | [產品策略](strategy.md)、[Ubiquitous Language](ubiquitous-language.md) |

1. 上游指定服務等級時優先採用；未指定時使用 Owner 的 `OperationalServiceProfile` 預設。
2. Archone 必須驗證合約授權、冷鏈、法規與 Facility capability 等硬條件。
3. 要求與硬條件不一致時進入人工確認，不自動覆寫後繼續。
4. 法規、安全、溫控或物理能力限制不可人工強制放行。
5. 待審期間只有 `OrderSubmission` 與不可變 revision：不建立正式 Order、不保留庫存、不建立 WMS 工作。
6. Owner 未回覆時持續等待；逾期只產生提醒、升級與 SLA 記錄，不自動核准或拒絕。

### DEC-003：Order 與 Fulfillment

| Metadata | 內容 |
| --- | --- |
| 狀態 | `Current`：單一 Facility Order；`Target`：FulfillmentCase、SLA 與人工例外 |
| 理由 | Archone 目前執行上游已決定的 fulfillment intent，不自行創造商業拆單；Case 只組合來源事實 |
| 重新檢討條件 | 出現無法以 OrderLine、Shipment reference 與 milestone 表達的獨立履約分支，或取得 DOM 權責 |
| 主要證據 | [Fulfillment orchestration](../architecture/fulfillment-orchestration.md)、`backend/fulfillment`、`e2e/spec` |

- 每張正式 Order 固定一個 Facility；跨 Facility 由上游拆成多張 Order。
- 是否允許部分出貨由上游指定，未指定時依 Owner policy；Archone 不自行做商業拆單。
- Target 中每張 Order 對應一個 `FulfillmentCase`，可關聯多個 Shipment，依 required milestones 結案。
- 暫不建立 `FulfillmentUnit`。只有出現無法用 OrderLine、Shipment reference 與 milestone 表達的獨立履約分支，
  才重新命名並建模。
- SLA 分為 Intake、Fulfillment 與 Exception Resolution；同時保存 wall-clock 與 accountable time。

### DEC-004：Inventory truth model

| Metadata | 內容 |
| --- | --- |
| 狀態 | `Current` |
| 理由 | 分離 movement intent、批次承諾與物化餘額，同時維持一個可原子提交與回復的 operation working set |
| 重新檢討條件 | 新增 assignment policy、source type、movement lifecycle state，或需要跨 operation 的一致性邊界 |
| 主要證據 | [Inventory 架構](../architecture/inventory.md)、`backend/inventory`、monolith integration tests |

- `StockOperation` 是同一 source unit、路線與 assignment policy 下的 movement group，不是 WMS work。
- `StockMove` 是 SKU 搬運意圖；`StockMoveLine` 是 Move 對具體 Quant／批次的承諾。
- `StockQuant` 保存物化的 on-hand 與 reserved；ATP 由兩者計算，不保存第三份餘額。
- Allocation planning 可以丟棄；只有同一交易內完成的 assignment 才是 committed fact。
- 現行 assignment policy 是 `SHIP_COMPLETE`：一個 operation 的所有 moves 必須原子滿足或完全不配置。
- Owner 是庫存、ACL、政策與未來計費的強制隔離維度；物理混放是另外的政策問題。

### DEC-005：Integration boundaries

| Metadata | 內容 |
| --- | --- |
| 狀態 | `Current` |
| 理由 | 讓 bounded-context business code 維持 transport-free，並確保狀態與 outbound publication 原子提交 |
| 重新檢討條件 | 引入新的 transport、跨 context transaction pattern，或現有 Outbox／Inbox 無法滿足一致性需求 |
| 主要證據 | [Messaging 維運](../operations/messaging.md)、root `AGENTS.md`、architecture／integration tests |

- Business code 以 Application Event 與 Publisher port 表達已發生事實；只有 infrastructure adapter 使用
  versioned Integration Event contract 與 `IntegrationEventPublisher`。
- Consumer 只做 contract-to-command normalization，不自行把 use case result 編排成下一個 outbound event。
- 同一完整 Application Event 可以由一個 adapter 原子轉成多個 audience-specific publications。
- Temporal Activity 是 command delivery boundary；terminal fact 仍由擁有該事實的 context 發布。
- `aggregateId` 表達 domain identity；`partitionKey` 表達 Kafka ordering／contention boundary，兩者不得混用。

## Open：尚待決定

| ID | 待決問題 | 何時必須決定 | 所需證據 |
| --- | --- | --- | --- |
| OPEN-001 | Events 與 Temporal 永久雙 runtime、由 Temporal 接管，或只保留 Events | 任一 runtime 要求獨有業務能力，或雙模式維護成本超過收益 | 語意對等測試、failure recovery、營運成本與 cutover rehearsal |
| OPEN-002 | Operational Service Policy 放在 Fulfillment／Logistics Data 或獨立 module；profile 採 snapshot、version reference 或兩者 | 開始實作 service profile 或待審受理流程 | policy 變更頻率、稽核需求、跨流程 consumer 與 effective dating |
| OPEN-003 | Control Tower 何時升格為 `Operational Work Management` context | assignment、escalation、responsibility clock 或 resolution policy 開始擁有獨立狀態轉移 | 工作生命週期、權責矩陣與來源 context 回寫需求 |
| OPEN-004 | Inbound／Returns 何時升格為獨立 bounded context | appointment、ASN、RMA、退款、整新或 disposition 形成獨立語言與規則 | 流程分支、SLA、外部 actor 與 WMS／Inventory 邊界 |
| OPEN-005 | 3PL Billing 的 activity、rate card、rating、adjustment 與 statement 模型 | 第一個可計費方案進入實作 | 計價樣本、effective dating、tax、ERP 邊界與更正流程 |
| OPEN-006 | 各 context 的 domain owner、schema owner、SLO、incident owner 與獨立部署門檻 | 進入正式 stage／prod ownership，或拆分 monolith | team ownership、failure isolation、deployment cadence 與容量資料 |
| OPEN-007 | 履約、庫存、例外與計費 KPI，以及 Core／Supporting／Generic 投資分類 | KPI 開始影響 roadmap 或 build／buy 決策 | 可量測資料、商業目標、差異化能力與維運成本 |

決定上述事項時，必須同步更新[策略](strategy.md)、[Context map](context-map.md)及對應的 executable tests；不要
新增另一套平行的規格庫。
