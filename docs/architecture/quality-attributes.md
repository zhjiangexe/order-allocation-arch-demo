# Architecture quality attributes

更新日期：2026-09-26

本文件把會驅動架構的品質需求轉成可驗收 scenario。它不是 production SLA：尚未取得實測基線或 business SLO
的數值明確標示 `Open measure`，不得以示意數字代替決策。

## Priority

| Priority | Quality | Why it matters |
| --- | --- | --- |
| P0 | Inventory consistency | Oversell、跨 Owner 消耗或錯誤釋放 reservation 會直接破壞 3PL 帳實一致性 |
| P0 | Idempotency and traceability | HTTP、Kafka、Temporal Activity／Signal 都可能重送，必須只有一次 business effect |
| P0 | Recoverability | Unknown outcome、broker outage 或 workflow interruption 不得被猜成成功或拒絕 |
| P0 | Owner isolation | `ownerId` 是 tenant 內庫存、政策、ACL 與未來計費的強制範圍 |
| P1 | Modifiability | Bounded context 與 transport boundary 必須能獨立演進，不洩漏 internal domain model |
| P1 | Operability | Outbox／Inbox、WAL、Kafka lag、DLT 與 workflow wait 必須可觀察及調查 |
| P1 | Performance and capacity | Hot SKU contention、backlog 與 recovery catch-up 不得壓垮 database |

## Scenario format

每個 scenario 使用 `Source → Stimulus → Environment → Artifact → Response → Measure → Evidence`。`Measure` 必須能由
automated test、metric、trace、reconciliation query 或 rehearsal 判斷，不使用「快速」、「高可用」等形容詞。

## Quality scenarios

### QA-001：Owner isolation

| Field | Definition |
| --- | --- |
| Status | `Current` domain／persistence constraint；`Target` request authorization |
| Source / stimulus | Caller 提交與 command owner 不一致的 Order line、stock supply、location 或 facility reference |
| Environment | Normal operation 或 replay |
| Response | 在建立或消耗 business state 前拒絕；不得讀取、承諾或扣用其他 Owner 的 inventory |
| Measure | Cross-owner state changes = 0；拒絕可由 stable error／audit identity 追蹤 |
| Evidence | Domain tests、schema constraints、allocation integration tests；application endpoint authentication／authorization 尚未完成 |

### QA-002：Atomic `SHIP_COMPLETE` assignment

| Field | Definition |
| --- | --- |
| Status | `Current` |
| Source / stimulus | 同一 StockOperation 中至少一個 move 無法完整滿足，或 concurrent writer 改變 candidate quant |
| Environment | Initial allocation 或 replenishment wake-up |
| Response | 整個 operation 不配置；不留下部分 move line 或 reserved quantity |
| Measure | Partial committed moves = 0；quant invariant 成立；失敗 transaction 無 Outbox terminal fact |
| Evidence | Inventory unit／integration／concurrency tests、[Inventory 架構](inventory.md) |

### QA-003：Duplicate delivery

| Field | Definition |
| --- | --- |
| Status | `Current` |
| Source / stimulus | 相同 HTTP business identity、message ID、Workflow Update 或 Activity 被重送 |
| Environment | Normal operation、consumer restart 或 replay |
| Response | 回傳既有 outcome 或穩定 duplicate result，不重複建立 Order、reservation、Shipment 或 publication |
| Measure | 每個 idempotency boundary 最多一個 committed business effect；內容不同的同 key 請求明確衝突 |
| Evidence | Inbox／idempotency contract tests、use-case replay tests、dual-mode E2E scenarios |

### QA-004：Transactional consumer failure

| Field | Definition |
| --- | --- |
| Status | `Current` |
| Source / stimulus | Handler、database write、mapping 或 follow-up publication 失敗 |
| Environment | Kafka consumer transaction |
| Response | Inbox claim、business rows 與 follow-up Outbox 一起 rollback；依分類 retry 或進 DLT |
| Measure | Partial committed effect = 0；retry 保留 original identity；DLT record 保留 recovery metadata |
| Evidence | Messaging integration／contract tests、[Messaging 維運](../operations/messaging.md) |

### QA-005：Kafka／connector outage

| Field | Definition |
| --- | --- |
| Status | `Current` recovery model；`Open measure` capacity window |
| Source / stimulus | Kafka unavailable、connector stopped 或 logical slot lag 持續增加 |
| Environment | Production-like traffic |
| Response | Business transaction 與 Outbox 保持原子；operations 在 WAL／disk capacity 耗盡前告警、恢復並受控 catch-up |
| Measure | 可支撐 outage 時間、最大 retained WAL、oldest Outbox age、catch-up time 與 data reconciliation 全部需在上線前定值 |
| Evidence | Outbox CDC integration test、messaging metrics／runbook；production capacity baseline 尚未建立 |

### QA-006：Cancellation versus handover race

| Field | Definition |
| --- | --- |
| Status | `Current` |
| Source / stimulus | Cancellation request 與 Shipment handover／Inventory completion 競爭或產生矛盾 outcome |
| Environment | Events 或 Temporal mode |
| Response | 只接受 owner context 的合法 terminal fact；無法證明可逆時不釋放 reservation，衝突進人工調查 |
| Measure | Illegal terminal transition = 0；unknown 不得轉成 rejection；同 request 的結果可重播 |
| Evidence | Cancellation use-case／workflow tests、[Fulfillment orchestration](fulfillment-orchestration.md) |

### QA-007：Temporal determinism and parity

| Field | Definition |
| --- | --- |
| Status | `Current` |
| Source / stimulus | Workflow replay、Activity retry、Signal 重送，或相同案例改以 Events driver 執行 |
| Environment | Temporal mode 與 dual-mode verification |
| Response | Workflow code 不直接讀 wall clock／random／I/O；兩種 driver 產生相同 owner-context terminal facts |
| Measure | Replay nondeterminism = 0；parity scenario 的 business outcomes 全部相同 |
| Evidence | Workflow tests、`e2e/spec` dual-mode matrix、semantic parity table |

### QA-008：Deployment-wide mode cutover

| Field | Definition |
| --- | --- |
| Status | `Current` design constraint；`Open measure` production rehearsal |
| Source / stimulus | Deployment 從 Events 切換 Temporal，或反向切換 |
| Environment | Multiple application replicas with in-flight fulfillment |
| Response | 停止新 intake、盤點並 drain／migrate in-flight state，所有 replicas 一次只啟用同一 mode，再做 reconciliation |
| Measure | Mixed-mode replicas = 0；無未歸屬 in-flight process；切換前後 canonical facts 可對帳 |
| Evidence | Deployment configuration validation、[Mode cutover gate](fulfillment-orchestration.md#mode-cutover-gate)；stage rehearsal 尚待執行 |

### QA-009：Hot SKU contention and latency

| Field | Definition |
| --- | --- |
| Status | `Current` correctness evidence；`Open measure` latency／throughput target |
| Source / stimulus | 多個 Order 同時競爭同一 owner／facility／SKU 的有限 supply |
| Environment | Burst traffic 與 optimistic-lock retry |
| Response | 依一致 lock order 與 bounded retry 完成可成功的 assignment；不得 oversell 或形成 retry storm |
| Measure | Oversell = 0、negative balance = 0；p95／p99 latency、retry attempts 與 sustainable throughput 必須以 benchmark baseline 定值 |
| Evidence | Hot-SKU concurrency integration／k6 tests；數值門檻尚未成為 business SLO |

### QA-010：Backup, restore and disaster recovery

| Field | Definition |
| --- | --- |
| Status | `Open` |
| Source / stimulus | PostgreSQL restore、lost logical slot、Kafka offset 與 database backup 不一致，或 Temporal persistence recovery |
| Environment | Stage／production incident |
| Response | 依受控 rebuild、event replay 與 business reconciliation 恢復，不以服務啟動成功代替資料完整性證明 |
| Measure | RPO、RTO、reconciliation completeness 與演練頻率在 production ownership 前定案 |
| Evidence | Messaging recovery runbook 已描述局部程序；完整 platform rehearsal 與 owner 尚未指定 |

## Architecture assurance matrix

| Quality scenario | Decision | Implementation boundary | Primary executable evidence | Runtime evidence／gap |
| --- | --- | --- | --- | --- |
| QA-001 | DEC-004、DEC-005 | Ordering／Inventory／Logistics Data | Domain、schema、allocation tests | API authorization gap |
| QA-002 | [ADR-0003](../adr/0003-inventory-commitment-model.md) | Inventory assignment transaction | Inventory concurrency／rollback tests | Contention metrics |
| QA-003／004 | [ADR-0002](../adr/0002-transactional-outbox-inbox.md) | Messaging + owner-context transaction | Inbox／Outbox／CDC tests | DLT／duplicate／lag metrics |
| QA-006／007／008 | [ADR-0004](../adr/0004-dual-orchestration-drivers.md) | Fulfillment + Temporal runtime | Workflow／dual-mode E2E | Cutover rehearsal open |
| QA-009 | DEC-004 | Inventory contention boundary | Integration + k6 benchmark | SLO threshold open |
| QA-010 | OPEN-006 | Deployment platform | Restore／reconciliation test not yet complete | RPO／RTO open |

## Maintenance rule

- 新 ADR 必須指出它服務或改變哪些 quality scenarios。
- 新 quality scenario 必須連結 executable evidence；沒有證據時標成 `Open`，不能假裝已保證。
- Production SLO、RTO、RPO 或容量門檻一旦定案，必須取代本文的 `Open measure` 並加入監控／runbook owner。
- 每次 architecture review 應同時檢查 scenario、ADR、code／tests 與 runtime evidence，不能只 review 圖。
