# Strategic Open Questions

狀態：第一輪定位已確認；本文件只保留仍未定案事項

完整決策見 [strategic-decisions.md](strategic-decisions.md)。

## 已確認，不再視為 open question

- 目前是 warehouse-centric 3PL（A），未來走 A+C；暫不成為 OMS／DOM（B）。
- `backend/fulfillment` 目標是正式的 **Outbound Fulfillment Operations bounded context**。
- Control Tower 是跨 context 控制面，不把 inbound、outbound、returns 合成 generic `OperationalCase`。
- OrderSubmission 待審期間不是正式 Order，不保留庫存；採不可變 revision、分級授權與人工衝突確認。
- 每張 Archone Order 固定一個 Facility；是否部分出貨由上游指定，未指定時依 Owner profile。
- 一張 Order 對應一個 FulfillmentCase，可關聯多個 Shipment；暫不建立 `FulfillmentUnit`。
- FulfillmentCase 依 required milestones 自動結案，並使用 Intake／Fulfillment／Exception 多時鐘 SLA。
- Local exception 先由來源 context 處理，符合提升條件才形成中央 ExceptionCase。
- Tenant 是 3PL provider；Owner 是 tenant 內的 ACL、庫存、政策與計費隔離範圍。
- Partner Integration／ACL 與 3PL Billing 保持獨立邊界。
- 法律合約與商業 Product Master 在外部系統；Archone 擁有 Operational Service／Product Profile。
- Inventory 所有權按 Owner 嚴格隔離；是否物理混放由政策決定。
- Returns 先作為獨立 capability/module；Inbound execution 留在 WMS／Inventory。
- 本輪不進行 Core／Supporting business subdomain 投資分類。

## 仍需決策

### 1. Inventory 的內部語言與 package migration

**文件意圖**：Movement、Allocation、Reservation、Position、Location 五個 domain module。

**現行 package**：Movement、Allocation、Balance、Location；`StockMoveLine` 暫在 Allocation。

**需要決定**：這是尚未完成的 migration、刻意簡化，還是舊文件已過時？

### 2. Events 與 Temporal 的長期產品策略

目前 Events 與 Temporal driver 透過設定互斥，但有平行的 process state representation。需要決定：

- 長期正式支援兩種 runtime。
- Temporal 是遷移／實驗路線，最後只保留 Events。
- Events 是過渡路線，最後由 Temporal 接管 durable workflow。

無論答案為何，runtime 都不得變成新的 domain truth，且 canonical terminal fact 仍由原 context 發布。

### 3. Operational Service Policy 的部署與 authority

業務所有權已確認，但仍需決定：

- 初期放在現有 Logistics Data、Fulfillment module，或建立獨立 module。
- Profile 如何由外部法律合約版本同步、審核與生效。
- Fulfillment 核准時採 snapshot copy、version reference，或兩者並存。

### 4. Control Tower 與 Operational Work Management 的分界

先以 projection、時間線、告警與工作清單實作。只有出現跨流程一致的 assignment、escalation、responsibility clock
與 resolution policy 時，才建立 `Operational Work Management` bounded context。需定義可量化的升格條件。

### 5. Returns 與 Inbound 的升格條件

- Returns：RMA、退款、換貨、整新、disposition、責任歸屬達到何種複雜度後拆成 Returns Management？
- Inbound：appointment、ASN coordination、vendor variance、detention SLA 是否會成為 WMS execution 之外的獨立能力？

### 6. 3PL Billing 的計價模型

仍需確認 storage charge、handling charge、VAS、minimum charge、tier、effective dating、tax 與 adjustment 的範圍，
以及 BillingStatement 對外 ERP 的責任切點。

### 7. Team、資料與部署所有權

需要為每個已確認／候選 context 指定：

- Domain owner 與維運 team。
- 資料庫 schema ownership。
- SLO／SLA 與 incident owner。
- modular monolith 內邊界，或未來獨立部署門檻。

### 8. KPI 與投資分類

待產品策略成立後，再決定 Core／Supporting／Generic business subdomain、自建／採購與研發優先序，並補上：

- Order acceptance lead time。
- Inventory／allocation correctness。
- On-time milestone completion。
- Exception resolution time。
- Billing accuracy 與 leakage。

## 建議確認順序

1. 先決定 Operational Service Policy 的落地位置與 Events／Temporal 長期方向。
2. 再定義 Control Tower、Returns、Inbound 的升格門檻。
3. 接著完成 Billing 計價模型與 context published language。
4. 最後對齊 team topology、資料 ownership、deployment 與產品投資分類。
