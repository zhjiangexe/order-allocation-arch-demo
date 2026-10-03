# Architecture Decision Records

更新日期：2026-09-26

ADR 保存 architecturally significant decision 的背景、替代方案、後果與驗證方式。只有會改變系統結構、品質屬性、
dependency、public interface、data ownership 或 construction／deployment technique 的決策需要 ADR；一般 refactor、
library patch version 與可逆 local implementation choice 不建立 ADR。

## Relationship to the decision register

- [決策與待決問題](../ddd/decisions.md)是精簡索引，回答「目前定了什麼、何時重開」。
- ADR 回答「當時有哪些 forces／選項、為什麼選擇、接受了哪些代價、如何證明」。
- ADR 不複製完整程式流程；Current implementation 仍以 code、migration 與 tests 為準。
- 被取代的 ADR 不刪除，改標 `Superseded by ADR-NNNN`，讓 Git history 與決策鏈保持可讀。

## Status

| Status | Meaning |
| --- | --- |
| `Proposed` | 尚待 stakeholder／evidence 確認，不得當成既定限制 |
| `Accepted` | 已定案；若標示 Target，表示方向接受但尚未完整實作 |
| `Deprecated` | 不建議新使用，但現行相依尚未移除 |
| `Superseded` | 已由另一 ADR 取代，保留供追溯 |

## Index

| ADR | Status | Decision register | Quality drivers |
| --- | --- | --- | --- |
| [ADR-0001：Modular monolith boundary](0001-modular-monolith-boundary.md) | `Accepted — Current` | DEC-001／OPEN-006 | Modifiability、deployment simplicity |
| [ADR-0002：Transactional Outbox／Inbox](0002-transactional-outbox-inbox.md) | `Accepted — Current` | DEC-005 | Consistency、idempotency、recoverability |
| [ADR-0003：Inventory commitment model](0003-inventory-commitment-model.md) | `Accepted — Current` | DEC-004 | Inventory integrity、concurrency |
| [ADR-0004：Dual orchestration drivers](0004-dual-orchestration-drivers.md) | `Accepted — Current strategy` | OPEN-001 | Recoverability、modifiability、parity |
| [ADR-0005：Outbound Fulfillment Operations](0005-outbound-fulfillment-operations.md) | `Accepted — Target` | DEC-001／DEC-003 | Operability、traceability、domain clarity |

## Template

```markdown
# ADR-NNNN: Short decision title

- Status: Proposed | Accepted | Deprecated | Superseded
- Date: YYYY-MM-DD
- Decision owners: role or team; use Open if not assigned
- Decision register: DEC-NNN / OPEN-NNN
- Quality scenarios: QA-NNN

## Context and forces
## Considered options
## Decision
## Consequences
### Positive
### Negative
### Neutral / follow-up
## Validation evidence
## Revisit triggers
## Related decisions
```

ADR 應以完整句子描述 decision 與 consequences。所有重大負面後果、營運成本與尚未驗證處都必須寫出來；不能只
記錄被選方案的優點。
