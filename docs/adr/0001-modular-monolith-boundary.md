# ADR-0001：Use a modular monolith as the current deployment boundary

- Status: `Accepted — Current`
- Date: 2026-09-26
- Decision owners: `Open — assign before production ownership`
- Decision register: DEC-001、OPEN-006
- Quality scenarios: Modifiability、deployment simplicity；production isolation measures remain open

## Context and forces

Archone currently needs strong transaction boundaries for inventory, ordering and messaging while the domain language and operational
ownership are still evolving. Ordering、Inventory、WMS、Logistics Data 與 Fulfillment 已有不同模型與 application boundary，
但沒有足夠的 team ownership、SLO、deployment cadence 或 failure-isolation evidence 支持立即拆成獨立 service。

共用 process／database 可以降低早期 deployment 與 integration 成本，但也容易讓 context 透過 internal table、entity
或 direct domain dependency 形成隱性耦合。

## Considered options

1. **Single layered monolith without domain boundaries**：最少初期結構，但無法保護 model ownership，拒絕。
2. **Modular monolith with explicit application／event boundaries**：保留部署簡單性，同時用 module、port 與 tests
   保護 domain boundary。
3. **Independent service per bounded context now**：提供 deploy／failure isolation，但在 ownership 與 SLO 尚未成熟時
   會先支付 network、contract、observability 與 data consistency 成本，延後。

## Decision

Archone Current 採單一 Spring Boot deployable 的 modular monolith。Bounded context 透過公開 application boundary 或
versioned Integration Event 合作；同 process／database 不代表可以共享 domain model 或讀取其他 context 的 internal
table。

Module 是否升格為 service 以 ownership、SLO、failure isolation、capacity 與 independent deployment need 為依據，
不以 package 數量或組織偏好決定。

## Consequences

### Positive

- 可以在單一 local transaction 內保護各 context 自己的 business state 與 Outbox publication。
- 本機開發、整合測試、migration 與部署拓撲較簡單。
- Bounded context 可先累積真實變更頻率與營運資料，再決定拆分。

### Negative

- Process、database 與 release cadence 仍共享；一個 context 的資源耗用可能影響其他 context。
- Boundary 必須依賴 architecture tests、review 與 disciplined ports，runtime 不會自動阻止違規存取。
- 未來拆分會需要 schema ownership、network contract、deployment 及 data migration 工作。

### Neutral / follow-up

- `integration-contracts`、`archone-framework` 與 `deployments:monolith` 是 contract／technical／assembly module，
  不是 bounded context。
- C4 Component view 顯示 bounded contexts；Container view 仍只有一個 backend deployable。

## Validation evidence

- `backend/settings.gradle` 的 context modules 與 monolith assembly。
- `MonolithPackageArchitectureTest`、Ordering／Fulfillment architecture tests。
- [Context map](../ddd/context-map.md)與 [C4 Model](../architecture/c4-model.md)。

## Revisit triggers

- 某 context 有明確 domain owner、schema owner、SLO 與 incident owner。
- Release cadence、capacity、security zone 或 failure isolation 需要獨立部署。
- Architecture tests 無法再有效控制跨 context coupling，且拆分效益大於 distributed-system 成本。

## Related decisions

- ADR-0002：Transactional Outbox／Inbox
- OPEN-006：組織、SLO 與獨立部署門檻
