# WMS／WES／WCS／TMS 動作與 Workflow 邊界 Review

> 狀態：**邊界決策保留；Temporal prototype 已移除，本文不代表現行 workflow engine contract**
> 目的：列出業界常見倉儲與運輸動作，逐項判斷應放在同步 Usecase、WMS Outbound 主流程、子流程、獨立 coordinator、WES／WCS 或 TMS。
> 設計限制：不把所有動作塞入單一技術 Workflow；不以中央 `while + Signal + switch(status)` 模擬整座倉庫。

## 1. 業界資料基線

不同產品會合併或拆分 WMS、WES、WCS。例如 SAP EWM 的 Material Flow System 可以直接連接 PLC，不一定需要額外 WCS；Honeywell 則把 WES、WCS、machine control 與 SCADA 視為可組合層。因此本文以「責任、時間尺度、事實 owner」分類，不以產品名稱判斷部署邊界。

- Oracle WMS 將 outbound flow 描述為 outbound order、wave planning、picking、packing、shipping confirmation 與 inventory update。[Oracle Outbound Orders](https://docs.oracle.com/en/cloud/saas/warehouse-management/26a/owmol/allow-expired-inventory-flag.html)
- SAP EWM 的典型 outbound 包含 wave、Pick-HU、packing、staging、loading 與 goods issue。[SAP EWM Master Guide](https://help.sap.com/doc/57b5f12b9ce84f3996c7bfa47c9dc81b/9.4/en-US/Master_Guide_EWM_94E.PDF)
- Honeywell 將 WES 定位為即時協調 picking、routing、capacity、workers 與多種 automation 的 execution layer。[Honeywell WES](https://automation.honeywell.com/us/en/software/warehouse-automation/momentum-warehouse-execution-system)、[Honeywell cloud WES announcement](https://automation.honeywell.com/us/en/news/press-releases/2025/honeywell-introduces-warehouse-execution-software-on-the-cloud)
- Honeywell 將 WCS 定位為 equipment、routing 與 material flow 的控制基礎；SAP MFS 也涵蓋 communication point、segment capacity、PLC telegram、故障與重新送訊息。[Honeywell warehouse automation](https://automation.honeywell.com/us/en/software/warehouse-automation)、[SAP MFS](https://help.sap.com/docs/SAP_EXTENDED_WAREHOUSE_MANAGEMENT/25cf88dfa94c49e4a440f3f1d752b8a1/4dc9cb53ad377114e10000000a174cb4.html)
- SAP TM 的 freight order 負責 transportation planning、carrier selection／tendering、transport resources 與 tracking；EWM 則執行 picking、packing、staging、loading，並可回報 truck departure。[SAP Freight Order Management](https://help.sap.com/docs/SAP_TRANSPORTATION_MANAGEMENT/54cf405c9d9e4c96bf091967ea29d6a7/ff8bbbea9bd0421b9f833793d8d52b3d.html)、[SAP EWM Transportation Unit integration](https://help.sap.com/docs/SAP_S4HANA_ON-PREMISE/e3dc5400c1cc41d1bc0ae0e7fd9aa5a2/f44d8a515b4b135ce10000000a445394.html)
- SAP EWM 的 inbound 從 transportation context 交給 warehouse processing 後，涵蓋 unloading、goods receipt、可選 deconsolidation／quality work 與 putaway；warehouse task 指定 putaway 目的地與路徑。[SAP EWM Inbound Process](https://help.sap.com/docs/SAP_S4HANA_ON-PREMISE/9832125c23154a179bfa1784cdc9577a%20/e15a4205bb284393add14c9d419fef45.html)、[SAP EWM Putaway Tasks](https://help.sap.com/docs/SAP_EXTENDED_WAREHOUSE_MANAGEMENT/3d97bec9bf1649099384bb8167df3cf2/ffc7cb53ad377114e10000000a174cb4.html)
- SAP EWM 的 warehouse request 也涵蓋 posting change、internal stock transfer 與 scrapping；Oracle WMS 則將 cycle count discrepancy 納入可核准、驳回重點或取消的 inventory-control 流程。[SAP EWM Warehouse Request](https://help.sap.com/docs/SAP_EXTENDED_WAREHOUSE_MANAGEMENT/3d97bec9bf1649099384bb8167df3cf2/64c8cb53ad377114e10000000a174cb4.html)、[Oracle WMS Implementation Guide](https://docs.oracle.com/en/cloud/saas/warehouse-management/24a/owmim/implementation-and-configuration-guide.pdf)

## 2. 四層責任

| 層 | 主要責任 | 常見時間尺度 | Source of truth | 不應負責 |
|---|---|---|---|---|
| WMS | Outbound order、庫存／HU／LPN、Warehouse Task、Pick／Pack／Stage／Load confirmations、warehouse exceptions | 秒到天 | 倉內業務狀態與庫存事實 | PLC 馬達控制、carrier tendering、配送追蹤 |
| WES | 動態工作釋放、優先序、waves／waveless orchestration、人力與設備負載平衡、跨 automation 協調 | 秒到小時 | execution plan／work queue；不一定擁有庫存 | Order promise、庫存帳、PLC I/O |
| WCS | HU／tote routing、conveyor／sorter／ASRS 指令、PLC telegram、設備 capacity／fault／ack | 毫秒到秒 | 即時設備與 material-flow state | 訂單、Shipment 商業狀態、長時間跨系統補償 |
| TMS | Freight order、carrier、tendering、route、appointment、transport execution、tracking、freight cost | 分鐘到週 | 運輸計畫與在途事實 | PickTask、pack station、倉內庫位與 conveyor 控制 |

### 2.1 邊界判斷規則

| 問題 | 是 | 否 |
|---|---|---|
| 是否改變倉內 Aggregate 且能在單一 transaction 完成？ | 同步 WMS Usecase | 繼續判斷 |
| 是否是 Shipment-level、需要 durable wait／取消／補償的階段？ | WMS Outbound 主流程 | 繼續判斷 |
| 是否有獨立 business key、SLA、多人／設備參與或可獨立取消？ | 子流程或獨立 coordinator | 繼續判斷 |
| 是否是多 Shipment 共用的 Wave／Replenishment capacity？ | 獨立 coordinator，不作單一 Shipment Child Workflow | 繼續判斷 |
| 是否需要即時重新排序人力／設備工作？ | WES | 繼續判斷 |
| 是否是高頻設備路由、PLC、heartbeat 或 jam recovery？ | WCS／PLC control loop，不進 Temporal | 繼續判斷 |
| 是否涉及 carrier、vehicle、route、tender、in-transit 或 delivery？ | TMS | 保持一般 Usecase／event／read model |

## 3. Process 分類符號

| 符號 | 類型 | 使用時機 |
|---|---|---|
| `U` | 同步 Usecase | 一次 transaction、立即成功或拒絕 |
| `M` | WMS Outbound 主流程 checkpoint | Shipment-level business checkpoint |
| `C` | 條件式獨立 process | 有獨立 key／SLA／取消／人工或長時間等待；不等於 Temporal Child Workflow |
| `K` | 獨立 Coordinator | Wave、Replenishment 等同時服務多 Shipment |
| `E` | Committed event | 由其他 owner commit 後通知主流程 |
| `WES` | WES execution service | 動態排程與資源平衡，不一定是 Temporal Workflow |
| `WCS` | WCS／PLC control loop | 高頻、低延遲設備執行，禁止放入業務 Workflow history |
| `TMS` | TMS flow | transport planning／execution／tracking |

## 4. WMS 動作完整盤點

### 4.1 Order intake、allocation 與 release planning

| 動作 | 業界常見 owner | 放入單 Shipment 主流程？ | 建議分類 | 判斷 |
|---|---|---:|---|---|
| 接收 outbound order／delivery | WMS | 否 | `U` | 驗證與保存後即可完成；由上游事件啟動 Workflow |
| 驗證 owner／facility／SKU／service level | WMS | 否 | `U` | Command validation，不是 durable phase |
| Order promise／跨倉 sourcing | OMS／Promising | 否 | 外部流程 | 不應搬入 WMS |
| facility 內庫存 allocation | WMS 或 Promising | 視架構 | `U`／外部結果 | 本專案由 order-promising 擁有 StockMove allocation |
| Wave selection／batching | WMS／WES | 否 | `K: WaveCoordinator` | 一個 Wave 對多 Shipment，不能作 Shipment child |
| Waveless dynamic release | WES | 否 | `WES` | 持續最佳化，不適合 per-Shipment Temporal loop |
| Cartonization／cubing 預計算 | WMS／WES | 否 | `U` | 多數為純決策；若需外部 rate／人工才另拆 |
| 產生 PickTask／Warehouse Task | WMS | 否 | `U` | 與 allocation／release transaction 一起成立 |
| Release Shipment／tasks | WMS／WES | 是 | `M` + Activity／Signal | 是 Pick 前的主流程交接點 |
| 優先序與 due-time 調整 | WES／WMS | 否 | `WES`／`U` | 更新 execution plan，不建立新 main phase |

### 4.2 Replenishment

| 動作 | Owner | 主流程？ | 建議分類 | 判斷 |
|---|---|---:|---|---|
| 偵測 pick-face 缺口 | WMS／WES | 否 | `U`／`WES` | 可由 Wave 或即時水位觸發 |
| 建立 replenishment task | WMS | 否 | `U` | 建立 warehouse movement 事實 |
| 決定 replenishment 優先序 | WES／WMS | 否 | `WES` | 與多 Shipment 共用 capacity |
| 人工補貨執行 | WMS | 否 | `K: ReplenishmentCoordinator` 或一般 task lifecycle | 常服務多 Shipment，不應綁死單一 Shipment |
| AS/RS／conveyor 補貨執行 | WES + WCS | 否 | `WES`／`WCS` | WCS 控制搬運；WMS收 completion fact |
| 補貨依賴 PickTask hold／release | WMS／WES | Pick phase 內 | `E` | Oracle WMS 支援補貨完成前 hold dependent picks，不需要新 main phase |
| 補貨失敗／無庫存 | WMS | Pick alternative | `E` → Short-pick resolution | 影響 Shipment 時才通知 main Workflow |

Oracle 將 picking-wave replenishment 描述為先建立補貨任務，並可在補貨完成前 hold 相依 PickTask；這支持「Replenishment 是共享 coordinator，Shipment Workflow 只等待結果」的切法。[Oracle replenishment-dependent picks](https://docs.oracle.com/en/cloud/saas/warehouse-management/26a/owmol/holding-or-releasing-replenishment-dependent-picking-tasks.html)

### 4.3 Picking

| 動作 | Owner | 主流程？ | 建議分類 | 判斷 |
|---|---|---:|---|---|
| 釋放／分派 PickTask | WES／WMS | Pick phase 入口 | `WES`／`U` | 不為每個 task 建 Workflow phase |
| 揀貨路徑／zone sequence | WMS／WES | 否 | `U`／`WES` | 最佳化決策，不是 durable wait |
| Scanner login／task accept | WMS | 否 | `U` | 操作 session |
| Scan location／SKU／LPN | WMS | 否 | `U` | 同步驗證，必須立即回應 |
| Confirm Pick quantity | WMS | 否 | `U` | Aggregate rule；不可繞 Temporal |
| Pick-to-light／robot pick command | WES／WCS | 否 | `WES`／`WCS` | 高頻設備 execution |
| 單 PickTask completed | WMS | 否 | event／read model | 不送入主 Workflow history |
| Shipment-level picking completed | WMS | 是 | `M` + `ShipmentPicked` Signal | 主流程由 Pick 進 Pack |
| Multi-zone picking completion | WMS／WES | 是 | `C: PickExecution` 或 event aggregation | 只有多 zone／多 tote／長 SLA 才拆 child |
| Short pick detection | WMS domain | Pick branch | `U` + `ShortPickDetected` | Workflow 不重算 requested／actual |
| Damage／missing／wrong item | WMS | Pick branch | `U`／exception event | 可現場修正者不升級主流程 |
| Pick cancellation | WMS／WES | Cancellation branch | `U`／`E` | 停止未開始 task；已搬貨則走 Putback |

### 4.4 Short-pick resolution

| 動作 | Owner | 主流程？ | 建議分類 | 判斷 |
|---|---|---:|---|---|
| 重新掃描／重數 | WMS | 否 | `U` | 操作修正，不建流程 |
| 同 facility alternate location | WMS／WES | Pick branch | `U` 或 `C` | 若立即可重派用 Usecase；需長等待才 child |
| 觸發 pick-face replenishment | WMS／WES | Pick branch | `K` + correlation | 等共享補貨完成，不把 Replenishment child 綁在 Shipment |
| 重派 PickTask | WMS／WES | Pick branch | `U` | 回 Pick phase |
| facility 庫存不足 | WMS | 是 | `E: ReallocationRequired` | 超出 WMS，自主流程進 `BLOCKED` |
| 跨倉重配／拆單／Backorder | Fulfillment／Promising／Ordering | 否 | 外部 workflow | WMS 等外部決策，不自行改 Order promise |
| Short-pick 人工決策 | WMS control desk 或 Fulfillment | 條件式 | `C: ShortPickResolutionWorkflow` | 有獨立 exception ID、SLA 時值得拆 child |

建議不要使用模糊的 `local short pick`；改用：

- `RESOLVED_WITHIN_FACILITY`
- `REPLENISHMENT_REQUIRED`
- `REALLOCATION_REQUIRED`
- `CANCELLATION_REQUIRED`
- `MANUAL_REVIEW_REQUIRED`

### 4.5 Consolidation、sortation 與 packing

| 動作 | Owner | 主流程？ | 建議分類 | 判斷 |
|---|---|---:|---|---|
| Zone picks consolidation | WMS／WES | 條件式 | `C: ConsolidationWorkflow` | 只有多 zone／多 tote 才插在 Pick 與 Pack 間 |
| Induct tote／LPN | WMS + WCS | 否 | `U` + `WCS` | WMS 記事實、WCS 控制輸送 |
| Sort destination／chute routing | WES／WCS | 否 | `WES`／`WCS` | 不進 Shipment Temporal history |
| Sort complete／all totes arrived | WMS／WES | 條件式 | `E` → consolidation child/main | Shipment-level 才通知 |
| 建立 PackTask／分派 pack station | WMS／WES | Pack phase 入口 | `U`／`WES` | 不是獨立 main phase |
| 掃描待包 SKU／LPN | WMS | 否 | `U` | 同步操作 |
| Carton selection／cartonization | WMS／WES | 否 | `U` | 演算法結果，不必 Workflow |
| 重量／材積／封箱驗證 | WMS／設備 | 否 | `U` | 失敗時回 pack exception |
| Shipping label／packing slip | WMS + carrier API | 否 | Activity／job | 需 retry，但通常不需獨立 Workflow |
| 危品／出口／合規文件 | WMS／ERP／TMS | 條件式 | `C: ComplianceWorkflow` | 有人工審核與長等待才拆 child |
| Repack | WMS | Pack phase 內 | `U` | 不建立新主 phase |
| Shipment-level packed | WMS | 是 | `M` + `ShipmentPacked` Signal | 主流程由 Pack 進 Stage |

Oracle 的 MHE integration 同時包含 wave pick output、route instructions，以及設備回傳 pick／pack／short confirmations，說明 business confirmation 應回到 WMS，而設備 routing 留在 automation layer。[Oracle MHE APIs](https://docs.oracle.com/en/cloud/saas/warehouse-management/26c/owmol/wms-apis-that-support-mhe-configuration.html)

### 4.6 Staging、dock、loading 與 departure

| 動作 | Owner | 主流程？ | 建議分類 | 判斷 |
|---|---|---:|---|---|
| Staging area determination | WMS，參考 TMS plan | Stage phase 入口 | `U` | 倉內 location 決策 |
| 搬到 staging lane | WMS／WES／WCS | 否 | task + `WES`／`WCS` | 主流程只等 Shipment-level staged |
| Shipment-level staged | WMS | 是 | `M` + `ShipmentStaged` Signal | 主流程進 loading／handover |
| Dock appointment | TMS／YMS | 否 | `TMS`／YMS | 可作 handover phase 的外部 prerequisite |
| Vehicle／trailer／transport unit | TMS；WMS保存 warehouse view | 否 | `TMS` + integration event | 不由 WMS建立 transport truth |
| Door assignment | WMS／YMS，使用 TMS appointment | 條件式 | `U`／`C: DockHandover` | 長時間等待車／門才拆 child |
| Load sequencing | WMS／WES，受 TMS load plan 約束 | Load phase | `WES`／`U` | execution decision，不逐件進主 Workflow |
| 實體 loading | WMS worker／WES／WCS | 是 | `M` 或 handover child | WMS 執行，TMS 提供 transport context |
| Loading finished | WMS | 是 | `E` 給 TMS | SAP EWM 可回傳 Loading Finished |
| Post Goods Issue／inventory issue | WMS／ERP inventory | 是 | `M`／Activity | warehouse business fact；與 transport tracking 分離 |
| Truck／Shipment departed warehouse | WMS 現場確認，TMS 消費 | 是 | `M` terminal + TMS event | 可由 WMS產生 departure fact，不代表 WMS擁有在途運輸 |
| In-transit／ETA／delivery／POD | TMS／carrier | 否 | `TMS` | 明確不在 WMS Outbound |

因此「Stage 之後全部屬 TMS」並不精確。建議邊界是：

```text
TMS：Freight plan / Carrier / Appointment / Transport Unit
                         ↓ transport context
WMS：Stage / Door / Physical Load / Goods Issue / Warehouse Departure
                         ↓ departure event
TMS：In Transit / ETA / Delivery / POD / Freight settlement
```

### 4.7 Cancellation、Putback 與例外

| 動作 | Owner | 主流程？ | 建議分類 | 判斷 |
|---|---|---:|---|---|
| Cancellation request | Fulfillment／Ordering | 全 phase cross-cutting | `M` Signal | 每個 phase 明確檢查，不走中央 dispatcher |
| 取消未 release 工作 | WMS／WES | Cancellation branch | `U` | 可立即完成 |
| 停止已 release 未執行 task | WMS／WES | Cancellation branch | `U`／WES command | WCS command 只能 best effort stop |
| 已 Pick 貨物 Putback | WMS | 條件式 | `C: PutbackWorkflow` 或 task lifecycle | 有多 movement、人工與 SLA 時適合 child |
| 取消 Pack／Stage work | WMS | Cancellation branch | `U` | 依 physical state 決定是否 Putback |
| 取消 WCS in-flight route | WCS | 否 | `WCS` | 不能假設設備立即停止；回 safe point fact |
| 已 load、尚未 depart | WMS + TMS | 條件式 | `C: UnloadOrHoldWorkflow` | 涉及 transport unit 與倉內反向搬運 |
| 已 depart | TMS／Returns | 否 | reject cancellation → Return flow | 不做資料 rollback |
| Automation cell unavailable | WCS → WES／WMS | 主流程可能 blocked | aggregate event | 不把每個 jam／heartbeat送 Temporal |
| Inventory discrepancy／damage | WMS | 條件式 | exception Usecase／child | 只有長時間人工決策才 child |

### 4.8 Inbound receiving 與 putaway

Inbound 不應塞進單一 Outbound process，而是以 inbound delivery／receipt 為 business key 的另一個主流程。

| 動作 | Owner | 放入 Inbound 主流程？ | 建議分類 | 判斷 |
|---|---|---:|---|---|
| ASN／inbound delivery intake | ERP／Supplier → WMS | 入口 | `U` + start event | 驗證並建立 canonical inbound receipt |
| Freight plan／dock appointment | TMS／YMS | 否 | `TMS`／YMS | WMS 只消費 arrival context |
| Vehicle check-in／door assignment | YMS／WMS | 條件式 | `C: InboundDockWorkflow` | 長時間等車或等門才拆 child |
| Unloading | WMS worker／WES／WCS | 是 | `M` | 主流程等 receipt-level unloaded，不追每 pallet |
| Scan HU／LPN／SKU／batch／expiry | WMS | 否 | `U` | 高頻同步驗證 |
| Quantity／overage／shortage／damage confirmation | WMS | Receiving branch | `U` + typed exception | 差異由 domain commit，不由 Workflow 重算 |
| Goods receipt posting | WMS／ERP inventory | 是 | `M`／Activity | 是庫存權利變更的關鍵事實 |
| Quality inspection／quarantine | WMS／QMS | 條件式 | `C: QualityInspectionWorkflow` | 有 sample、人工決策與 SLA 才 child |
| Deconsolidation／repacking | WMS／WES | 條件式 | `C` 或 task lifecycle | 複雜 inbound profile 才形成 durable phase |
| Putaway strategy／destination bin | WMS | 否 | `U` | 決策與 warehouse-task creation，不是 wait |
| Putaway task execution | WMS／WES／WCS | 是，但只等 aggregate | `M` + task lifecycle | 每個 WT／route 不進主 Workflow |
| Receipt-level putaway completed | WMS | 是 | `M` terminal | Inbound Workflow 在倉內上架完成後結束 |

建議 Inbound 主流程：

```text
Create Receipt
  → Await Unloading
  → Confirm Goods Receipt
  → [optional Quality / Deconsolidation]
  → Await Putaway Completion
  → Inbound Completed
```

### 4.9 Inventory control、internal movement 與 replenishment

| 動作 | Owner | 是否屬 Outbound child？ | 建議形態 | 判斷 |
|---|---|---:|---|---|
| Ad-hoc bin／HU transfer | WMS | 否 | `U` + Movement task | 是獨立倉內行為 |
| Posting change／stock-status change | WMS／ERP inventory | 否 | `U`；需核准時 `C` | 不依附特定 Shipment |
| Replenishment | WMS／WES | 否 | `K: ReplenishmentCoordinator` | 可服務多張 Shipment |
| Cycle-count task creation | WMS | 否 | `K` 或 batch job | 以 count plan／location set 為 key |
| Scan／count confirmation | WMS | 否 | `U` | 高頻同步作業 |
| Discrepancy recount／approval | WMS control desk | 否 | `C: InventoryDiscrepancyCase` | 有獨立 case ID、核准與 SLA |
| Inventory adjustment posting | WMS／ERP inventory | 否 | Activity／outbox event | 只在核准後 commit |
| Scrapping／write-off | WMS／ERP／Finance | 否 | `C: ScrappingApprovalWorkflow` | 有財務／法遵核准才拆 workflow |
| Slotting／re-slotting | WMS／WES optimizer | 否 | planning service + movement batch | 最佳化不是 per-Shipment phase |

Outbound Picking 可等待 replenishment 的 correlation outcome，但不應成為 replenishment 的 owner；取消 Shipment 也不得連帶取消正在服務其他 Shipment 的共享補貨。

### 4.10 Returns 與 reverse logistics

| 動作 | Owner | 放入 Outbound 主流程？ | 建議形態 | 判斷 |
|---|---|---:|---|---|
| Return authorization | OMS／Customer Service | 否 | 外部 workflow | WMS 不決定商業退貨權 |
| Return transport／pickup | TMS／carrier | 否 | `TMS` | reverse transport lifecycle |
| Return receipt／check-in | WMS | 否 | `WmsReturnReceivingWorkflow` | 獨立 receipt／return ID |
| Inspect／grade／disposition | WMS／QMS／Returns | 否 | `C: ReturnDispositionWorkflow` | 可能人工等待並分流 |
| Restock／repack／refurbish | WMS／Repair | 否 | task lifecycle／child | 依 disposition 結果執行 |
| Scrap／return-to-vendor | WMS／ERP／TMS | 否 | child／外部 flow | 需財務、供應商或運輸交界 |
| Refund／replacement | OMS／Finance | 否 | 外部 workflow | 只消費 WMS inspection outcome |

Return 是新的 reverse-logistics process，不是已完成 outbound 的 rollback。

### 4.11 Cross-docking 與 Yard／Dock coordination

| 動作 | Owner | 建議形態 | 與 WMS 主流程的關係 |
|---|---|---|---|
| Opportunistic／planned cross-dock matching | WMS／Fulfillment | `K: CrossDockCoordinator` | 關聯 inbound receipt 與 outbound demand，不作任一方 child |
| Inbound-to-outbound staging move | WMS／WES／WCS | task lifecycle | 雙方主流程只收 aggregate facts |
| Gate／yard check-in | YMS／TMS | YMS service／event | WMS 使用 transport-unit arrival |
| Yard movement／parking spot | YMS／WCS-like yard control | YMS execution | 不放入 Warehouse Shipment history |
| Door appointment／assignment | TMS／YMS／WMS | `C: DockWorkflow` 或 Usecase | 只有長時間等待與獨立 SLA 才 child |
| Detention／demurrage exception | TMS／YMS | TMS exception workflow | WMS 提供 loading／unloading timestamps |

SAP EWM 支援在 inbound putaway 前判斷是否直接滿足 outbound demand，也支持無法 cross-dock 時回到標準 putaway；這正是使用獨立 coordinator、不把兩個主流程硬合併的情境。[SAP EWM Opportunistic Cross-Docking](https://help.sap.com/docs/SAP_EXTENDED_WAREHOUSE_MANAGEMENT/3d97bec9bf1649099384bb8167df3cf2/d349397317d64478aea5e1717d1dee95.html)

## 5. WES 動作與是否需要 Temporal

| WES 動作 | 建議實作形態 | 是否進 WMS 主流程 | 理由 |
|---|---|---:|---|
| 動態 order／task release | WES scheduler／optimizer | 只回 Shipment released fact | 高頻重算，不適合長 history |
| Wave／waveless orchestration | `WaveCoordinator` 或 WES engine | 否 | 多 Shipment 共享 coordinator |
| Labor／robot／station capacity balancing | WES control service | 否 | 連續最佳化 |
| Work prioritization／deadline recovery | WES control service | 否 | 改 execution order，不改 Shipment truth |
| Multi-zone task orchestration | WES | 可回 Pick complete／blocked | 主流程只收 aggregate result |
| Replenishment dependency coordination | WES／Replenishment coordinator | Pick phase 只等待 | 共享 resource |
| Conveyor／ASRS work release | WES → WCS | 否 | equipment-facing command |
| Work exception escalation | WES → WMS event | 必要時 `BLOCKED` | 只升級無法自動恢復的 business exception |
| Throughput／queue monitoring | Metrics／read model | 否 | Observability，不是 Workflow state |

WES 不一定要用 Temporal。只有具備明確 batch key、可結束、可取消與 durable SLA 的 Wave／exception case 才適合 Workflow；持續運行的最佳化 loop 應留在 WES scheduler。

## 6. WCS／PLC 動作：不得放入業務 Workflow

| WCS 動作 | Owner | 對 WMS／WES輸出 | 為何不進 Temporal |
|---|---|---|---|
| PLC connection／channel lifecycle | WCS | availability／fault summary | 高頻技術狀態 |
| Telegram send／ack／resend | WCS | command result／alarm | 已有設備協定 retry |
| Barcode／RFID identification | WCS | HU identified／unknown | 毫秒級 |
| Induct／divert／merge／sort | WCS | communication-point arrival | 大量事件 |
| Conveyor segment routing | WCS | route completion／blocked | 即時路由 |
| AS/RS crane move | WCS／PLC | HU arrived／fault | physical control loop |
| Capacity／buffer／chute occupancy | WCS | aggregated capacity | 變化頻繁 |
| Weight／dimension／contour check | WCS equipment | passed／exception | 設備檢查 |
| Jam／fault／emergency stop | PLC／WCS | cell unavailable／recovered | safety-critical，不可依賴 Workflow task scheduling |
| Manual bypass／clarification lane | WCS + operator | exception resolved／diverted | 局部設備 recovery |

SAP MFS 將 warehouse task 拆成 communication points 間的 stages，並由 PLC 控制兩點之間的實際動作；只有 subordinate controller 無法決定的位置才設 decision point。這與本文「業務 Workflow 不追每段 conveyor move」的原則一致。[SAP MFS structure](https://help.sap.com/docs/SAP_EXTENDED_WAREHOUSE_MANAGEMENT/25cf88dfa94c49e4a440f3f1d752b8a1/39cbcb53ad377114e10000000a174cb4.html)

## 7. TMS 動作與 WMS 交界

| TMS 動作 | TMS workflow／service | WMS 是否參與 | 交界事件 |
|---|---|---:|---|
| 建立 transportation requirement／freight unit | TMS | 提供重量／材積／ready date | `TransportRequirementCreated` |
| Freight order／booking | TMS | 否 | `TransportPlanned` |
| Carrier selection／tendering | TMS | 否 | `CarrierConfirmed／Rejected` |
| Route／mode／resource planning | TMS | 否 | `TransportPlanConfirmed` |
| Dock／pickup appointment | TMS／YMS | WMS 使用結果 | `PickupAppointmentConfirmed` |
| Trailer／vehicle arrival | TMS／YMS | WMS check-in／door | `TransportUnitArrived` |
| Loading authorization | TMS + WMS | 是 | `ReadyForLoading` |
| Physical loading | WMS | TMS 接收進度 | `LoadingStarted／Finished` |
| Warehouse departure | WMS 確認，TMS追蹤 | 是 | `ShipmentDeparted` |
| In-transit milestones／ETA | TMS／carrier | 否 | TMS events |
| Delivery／POD | TMS／carrier | 否 | `Delivered` |
| Freight charge estimation／settlement | TMS／Finance | 否 | settlement events |

## 8. 建議 Process 階層

下圖表達 business ownership 與 correlation，不代表目前已建立 Temporal parent／child Workflow：

```text
OrderFulfillment integration                ← 跨 context，未來才評估 orchestrator
├─ Allocation process                       ← Promising／Inventory
├─ WmsOutboundProcess                       ← 一張 Warehouse Shipment 主流程
│  ├─ [conditional] ShortPickResolutionProcess
│  ├─ [conditional] ConsolidationProcess
│  ├─ [conditional] DockHandoverProcess
│  └─ [conditional] PutbackProcess
├─ WaveCoordinator                          ← 獨立 K；多 Shipment 共用，不是 child
├─ ReplenishmentCoordinator                 ← 獨立 K；多 Shipment 共用，不是 child
└─ TransportExecutionWorkflow               ← TMS，與 WMS 以事件交接

WES scheduler                               ← 持續 execution optimization，不是 root Workflow
└─ WCS／PLC control loops                    ← 設備控制，絕不成為 Temporal child
```

### 8.1 為何 Wave／Replenishment 不是 Shipment child

- 一個 Wave 可能包含多張 Shipment。
- 一次 replenishment 可能解除多個 PickTask／Shipment 的缺口。
- 由單一 Shipment parent 擁有會造成錯誤 ownership、取消傳播與生命週期耦合。
- 建議使用獨立 coordinator key，透過 event／correlation 通知各 Shipment。

### 8.2 何時才拆獨立 Process

同時符合多項才拆：

- 有獨立 business key，例如 `shortPickCaseId`、`putbackId`、`dockHandoverId`。
- 可能等待分鐘以上、人工／設備／外部系統。
- 有自己的 timeout、retry、取消與操作介面。
- parent 只需要 final typed outcome，不需要知道內部步驟。
- 未來可能獨立演進或被其他流程重用。

若只是一次 repository transaction、一次 scanner validation 或一個演算法，維持 Usecase，不建立獨立 process。即使拆出 process，也不代表必須使用 Temporal。

### 8.3 整體 WMS 應是多個 process family，不是一個超大流程

```text
WmsInboundProcess                          ← inbound delivery / receipt
├─ [conditional] InboundDockProcess
├─ [conditional] QualityInspectionProcess
└─ [conditional] DeconsolidationProcess

WmsOutboundProcess                         ← warehouse shipment
├─ [conditional] ShortPickResolutionProcess
├─ [conditional] ConsolidationProcess
├─ [conditional] DockHandoverProcess
└─ [conditional] PutbackProcess

WmsReturnReceivingProcess                  ← return receipt
└─ [conditional] ReturnDispositionProcess

Wave / Replenishment / CrossDock           ← 獨立 coordinators，服務多張單
InventoryDiscrepancy / Scrapping           ← 獨立 case workflows
WES scheduler / WCS control loops          ← 不成為上述 workflow 的設備步驟
TMS processes                              ← 以 appointment / arrival / departure events 交界
```

這些 process family 先由各自的 bounded context 實作。只有符合 [Temporal 採用決策](./temporal-adoption-decision.md) 的門檻後，才新增具名的跨系統 orchestration module。

## 9. 建議 WMS Outbound checkpoints

### 9.1 Phase 切分

| Checkpoint | 主流程等待的事實 | 可啟動的獨立 process／coordinator | 不追蹤的細節 |
|---|---|---|---|
| `PREPARING` | Shipment created／released | Wave correlation（進階模式） | allocation algorithm、task rows |
| `AWAITING_PICK_COMPLETION` | `ShipmentPicked` | ShortPickResolution；等待 Replenishment coordinator | 每筆 scan／PickTask／robot command |
| `AWAITING_CONSOLIDATION`（optional） | `ShipmentConsolidated` | ConsolidationProcess | tote 每次 divert |
| `AWAITING_PACK_COMPLETION` | `ShipmentPacked` | ComplianceProcess（optional） | 每件裝箱與 label print log |
| `AWAITING_STAGE_COMPLETION` | `ShipmentStaged` | DockHandover precondition | 每段 warehouse move |
| `AWAITING_LOADING_OR_DEPARTURE` | `LoadingFinished`／`ShipmentDeparted` | DockHandoverProcess | 每 pallet load／vehicle telemetry |
| `CANCELLING` | `CancellationCompleted／Rejected` | PutbackProcess（conditional） | 每筆 reverse move |
| `BLOCKED` | typed external decision | short-pick／transport exception case | 技術 retry state |

### 9.2 基本倉與自動倉的共同主流程

主流程只依賴 Shipment-level committed facts，因此 manual WMS、WES 或 WCS 都能接入：

```text
Manual warehouse:
Station API → WMS Usecase → Shipment-level Event → WMS process transition

Automated warehouse:
WES → WCS／PLC → WMS confirmation API → Shipment-level Event → WMS process transition
```

### 9.3 Implementation 原則

- 由具名 Usecase 處理單一 transaction，由 domain model 驗證狀態轉換。
- 跨 transaction 的 WMS process 保存自己的 business key、目前 checkpoint 與 terminal outcome。
- Process 只消費 committed events，不接收每筆 scan、PickTask 或 PLC 訊號。
- Picking 內可以有「短揀 → 補貨 → 恢復」的局部循環，但不建立全域 `processNextOutcome()` dispatcher 模擬整座倉庫。
- 目前不建立 Temporal Signal／Activity contract；未來若符合 ADR 門檻，再由既有 commands 與 events 映射。

## 10. Commands 與對外事件邊界

| WMS command／event | 現在的 owner | 對外粒度 |
|---|---|---|
| Create／Release Shipment | WMS Usecase | `ShipmentCreated`／`ShipmentReleased` |
| Confirm Pick／Short Pick | WMS Picking process | Shipment-level picked 或 typed short-pick outcome，不外洩逐筆 scan |
| Pack／Stage | WMS Packing／Dispatch process | `ShipmentPacked`／`ShipmentStaged` |
| Load／Depart | WMS Loading／Handover process | `LoadingFinished`／`ShipmentDeparted`，供 TMS 接續 |
| Request Cancellation | WMS Cancellation／Recovery process | accepted／completed／rejected；Putback 細節留在 WMS |
| PLC heartbeat／route／jam | WCS | 不發布成跨系統 shipment business fact |
| Carrier booking／tender | TMS | WMS 只消費 appointment／arrival／loading authorization 結果 |

## 11. 套用到目前專案

### 11.1 已有能力

| 已有項目 | 建議定位 |
|---|---|
| `CreateShipmentUsecase`／`ShipmentCreated` | WMS outbound intake command／fact |
| `PlanWaveUsecase`／`WavePlanned` | 多 Shipment release planning；priority／cutoff／容量 greedy selection |
| `ReleaseWaveUsecase`／`WaveReleased` | 建立 WarehouseWork／PickTasks，並發布各 Shipment release facts |
| `CompleteWaveUsecase`／`WaveCompleted` | 所有 picking work 完成或取消時關閉 Wave |
| `ConfirmPickUsecase`／`ShipmentPicked`／`ShortPickDetected` | Picking command 與 committed outcomes |
| `PackShipmentUsecase`／`ShipmentPacked` | Packing command／fact |
| `StageShipmentUsecase`／`ShipmentStaged` | Dispatch preparation command／fact |
| `HandOverShipmentUsecase`／`ShipmentHandedOverToCarrier` | 目前 WMS custody terminal；未來通知 TMS |
| `CancelShipmentUsecase` | Cancellation／Recovery 入口；仍需 Putback lifecycle |

### 11.2 尚未建立，不應在本次假裝完成

- Waveless continuous release／Wave template persistence 與 scheduler。
- Replenishment task／coordinator。
- Short-pick resolution Usecases。
- Consolidation／sortation。
- Carton／LPN／label／compliance。
- Loading、dock door、transport unit。
- Putback task lifecycle。
- WES／WCS integration ports。
- TMS freight order、carrier、appointment、tracking。

### 11.3 Temporal prototype 移除後的範圍

1. 保留 `wms` 的 Shipment-level commands、domain events 與 domain state transitions。
2. 移除 `fulfillment-workflow*` contracts、Activity adapters 與 runtime，不把它們視為已承諾的整合 API。
3. Wave／Pick／Pack／Stage／Handover 由 WMS business process 表達，不保存第二份 orchestration state。
4. Short pick、Putback、Loading、WES／WCS 與 TMS 仍依本文件的 ownership 原則逐步補齊。
5. 未來只有跨系統 durable coordination 成立時，才從穩定的 WMS boundary events 建立新的具名 Workflow。

## 12. 建議後續 Task Gate

| Gate | 內容 | 先決條件 |
|---|---|---|
| `DESIGN-01` ✓ | Review 本文件動作分類與 WMS／TMS 邊界 | 無 |
| `WAVE-01` ✓ | Wave planning／release／completion 與 WarehouseWork generation | `DESIGN-01` |
| `PROCESS-01` | 在 `wms` 定義 outbound process identity、route 與 checkpoint | `DESIGN-01` |
| `EVENT-01` | 穩定 Shipment-level committed events 與冪等消費語意 | `PROCESS-01` |
| `SHORT-01` | ShortPickResolution application contract | WMS Usecase outcome gate |
| `PUTBACK-01` | Putback／Recovery lifecycle | cancellation persistence gate |
| `AUTO-01` | WES／WCS integration ports | automation scope 確認 |
| `TMS-01` | Freight／appointment／tracking integration contract | TMS scope 確認 |

## 13. 請 Review 的核心決策

| # | 核心決策 | 是 | 否 | 說明 | Review |
|---:|---|:---:|:---:|---|---|
| 1 | 是否採用 Release → Pick → optional Consolidate → Pack → Stage → Load／Depart 作為 Main WMS checkpoints？ | ✓ |  | 只保留 Shipment-level business checkpoints，不追蹤每筆現場作業。 | 已採用 |
| 2 | 每筆 PickTask／scan 是否進跨系統 orchestration history？ |  | ✓ | 這些是高頻同步 Usecase；對外只發布 Shipment-level completion 與 exception。 | 已採用 |
| 3 | Wave 是否為 Shipment 子流程？ |  | ✓ | 改為獨立 `WaveCoordinator`；一個 Wave 可同時包含多張 Shipment，不應由任一 Shipment parent 擁有。 | 已採用 |
| 4 | Replenishment 是否為 Shipment 子流程？ |  | ✓ | 改為獨立 `ReplenishmentCoordinator`；一次補貨可解除多張 Shipment 的缺口，只以 correlation 通知結果。 | 已採用 |
| 5 | Short-pick 是否為 Picking 主分支？ | ✓ |  | Short pick 是 Picking 的具名業務分支；只有具獨立 key／SLA 時才拆成獨立 process。 | 已採用 |
| 6 | Consolidation 是否為固定 phase？ |  | ✓ | 依 warehouse profile 啟用；單區、單 tote 出庫不需要空階段。 | 已採用 |
| 7 | Loading 是否放在 WMS main？ | ✓ |  | WMS 執行實體 loading 與確認；TMS 提供 freight order、vehicle 與 transport plan。 | 已採用 |
| 8 | `ShipmentDeparted` 是否可作 WMS terminal fact？ | ✓ |  | 它表示 warehouse departure confirmed，不表示 WMS 擁有 in-transit／delivery。 | 已採用 |
| 9 | Putback 是否可形成獨立 Recovery process？ | ✓ |  | 只有長時間、多 movement、人工 SLA 或獨立 key 時才拆分；簡單回庫維持 WMS task lifecycle。 | 已採用 |
| 10 | WES continuous optimizer 是否使用 Temporal？ |  | ✓ | 持續重算的 optimizer 留在 WES scheduler；有終點的 Wave／exception case 才考慮 Workflow。 | 已採用 |
| 11 | WCS equipment steps 是否進 Temporal？ |  | ✓ | PLC、route、heartbeat 與 jam recovery 留在 WCS；只向 WMS 輸出 aggregate business fact。 | 已採用 |
| 12 | TMS 是否納入目前 module？ |  | ✓ | 先定義 integration boundary；WMS 只消費 transport context 並發布 loading／departure facts。 | 已採用 |
| 13 | WMS process implementation 是否允許中央 dispatcher？ |  | ✓ | 使用具名 process／checkpoint；只在業務真實可重複的單一 process 使用局部 loop。 | 已採用 |

Temporal 是否採用以 [Temporal 採用決策](./temporal-adoption-decision.md) 為準。
