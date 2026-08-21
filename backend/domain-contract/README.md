# Domain Contract

`domain-contract` 是 framework-neutral 的 Design by Contract runtime，核心提供：

- Precondition、Postcondition、Invariant phase。
- `BooleanSupplier` lambda DSL。
- 接近 uContract 的 fail-fast 語意。
- 參照 uContract，以環境變數初始化全域及各 phase 開關。
- Exception 攜帶 plain diagnostic message 與 evaluation cause。
- 零 production runtime 第三方相依。
- 只以 `Contract` 作為 contract 撰寫入口。

## 使用方式

Consumer module 加入：

```groovy
implementation project(':domain-contract')
```

`Contract` 與 uContract 一樣提供 static method；若偏好簡潔寫法，可以 static import：

```java
import static com.flowzati.archone.contract.Contract.ensure;
import static com.flowzati.archone.contract.Contract.invariant;
import static com.flowzati.archone.contract.Contract.require;
import static com.flowzati.archone.contract.Contract.requireNotNull;
```

撰寫 contract。條件放前面，plain diagnostic message 放後面：

```java
ensure(
        () -> reservedQuantity == oldReservedQuantity + quantity,
        "Reserving stock must increase reserved quantity by the requested amount");

ensure(
        () -> onHandQuantity == oldOnHandQuantity,
        "Reserving stock must not change on-hand quantity");
```

Invariant：

```java
invariant(
        () -> onHandQuantity >= 0,
        "On-hand quantity must not be negative");
```

Precondition：

```java
require(
        () -> id.equals(line.stockQuantId()),
        "Move line belongs to another stock quant");

requireNotNull(line, "Move line must be present");
```

這類 `require` 用來表示內部 caller 必須履行的責任。外部輸入 Validation、正常 Domain rejection、安全與資料
完整性保護必須 always-on，不能只依賴可關閉的 precondition。

Contract 採 fail-fast：第一個失敗條件會立即拋出 exception，後續條件不再執行。Precondition 失敗會在 Method Body
執行前拋出 `PreconditionViolationException`。Postcondition 與 Invariant 分別拋出
`PostconditionViolationException` 與 `InvariantViolationException`。

Message 是簡短、固定的開發診斷文字，不作為終端使用者訊息。動態 request、tenant 與 trace context 應由
logging／tracing 提供，不塞進 Contract API。

## Contract 開關

`Contract` 第一次載入時，會在 `static {}` 讀取與 uContract 相同命名的環境變數：

| 環境變數 | 對應欄位 | 效果 |
| --- | --- | --- |
| `DBC=off` | `Contract.DBC` | 關閉所有 contract |
| `DBC_PRE=off` | `Contract.CHECK_PRE` | 關閉 precondition |
| `DBC_POST=off` | `Contract.CHECK_POST` | 關閉 postcondition |
| `DBC_INV=off` | `Contract.CHECK_INV` | 關閉 invariant |

沒有設定或不是精確的 `off` 時預設啟用。這些欄位參照 uContract 保持為 public mutable static，測試可以暫時切換，
但必須在結束後還原；production 不應在多執行緒請求期間動態修改。停用時連 predicate 都不會執行。

內部也參照 uContract 使用 `ThreadLocal` re-entry guard。若 predicate 間接呼叫另一個 contract，巢狀 contract
會被略過，避免無限遞迴；guard 會在 `finally` 中還原。

## Exception 與診斷資料

`domain-contract` 不解析或翻譯訊息。失敗時 exception 攜帶：

- `phase()`
- `getMessage()`
- predicate 的 evaluation cause。

這些資料只供測試、log 與問題診斷。公開 REST API 遇到 Contract violation 時，應回傳固定的通用 `500` 與
incident ID，不應直接把 Contract message 或 cause 顯示給使用者；詳細處理方式見
[Design by Contract 開發手冊的 Global Exception Handler](../../docs/design-by-contract-development-guide.md#112-global-exception-handler)。

## 設計限制

- Contract predicate 必須是 pure，不得修改狀態或呼叫外部服務。
- Contract 採 fail-fast，不收集同一 phase 的後續 violation。
- 全域開關是 process-wide，不是 request-scoped feature flag。
- Validation、Authorization 與必要 Domain Rule 不得只靠 DbC enforcement。
- 舊狀態應以 primitive 或 immutable snapshot 明確保存；本模組不以 Jackson deep copy 實作 `old()`。
- `ensureAssignable()` 尚未提供；需要時應以明確 snapshot 或 bounded-context-specific comparison 實作。
