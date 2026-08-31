# Design by Contract 開發手冊

狀態：團隊開發指引
日期：2026-08-21
適用範圍：Java、DDD、Event Sourcing，以及使用 coding agent 的開發流程。

本文以 [uContract](https://gitlab.com/TeddyChen/ucontract) 與專案內建
[`domain-contract`](../backend/domain-contract/README.md) lambda DSL 為參考，說明 Design by Contract
（DbC）與 Validation、Domain Rule、Test 的分工。

## 1. 團隊決策摘要

1. DbC 用來表達軟體元件之間的責任，不是通用 Validation framework。
2. `require` 表達 caller programmer obligation；`ensure` 表達 method 的結果承諾；`invariant` 表達
   object／Aggregate 必須維持的狀態。
3. 外部輸入錯誤、正常業務拒絕、安全與資料完整性 enforcement 必須 always-on，不得只依賴可關閉的
   DbC。
4. Aggregate Root 的純 `require` 通常很少；Value Object 會消除大部分 blank、positive 等 primitive 檢查。
5. `ensure` 與 `invariant` 只保留不容易從 implementation 直接看出、而且能抓到實作錯誤的規格。
6. Test 負責產生情境與路徑，Contract 是路徑中的 correctness oracle。DbC 可以縮短重複 assertion，不能
   取代重要測試情境。
7. Contract 理論上可關閉，因為正確程式的業務語意不得依賴 Contract；完整測試只增加關閉監控的信心，
   並不是可關閉的根本理由。
8. `domain-contract` 只以 `Contract` 作為公開撰寫入口，避免形成語意重疊的平行 assertion API。

## 2. 從責任理解 DbC

DbC 將 method 視為 caller 與 callee 之間的契約：

```text
Precondition / require
    Caller 在呼叫前必須保證

Method body
    Callee 執行行為

Postcondition / ensure
    Method 正常完成後必須保證

Invariant
    Object 在公開操作前後必須成立
```

可簡化為：

```text
{ Precondition } Method { Postcondition }
```

失敗責任如下：

| Contract | 誰違反承諾 | 典型意義 |
| --- | --- | --- |
| `require` | Caller | Application／其他元件錯誤使用 API |
| `ensure` | Callee | Method implementation 沒有產生承諾結果 |
| `invariant` | Object implementation | Aggregate 完成操作後進入不合法狀態 |

DbC 的重點不是整理 `null`、`blank`、`empty`、`positive` 等檢查種類，而是回答：

> 條件失敗時，哪一個軟體元件違反了承諾？

看起來同樣是 assertion 的條件，可能分屬不同責任：

```java
// Internal caller obligation：line 必須屬於 receiver Aggregate
require(
        () -> id.equals(line.stockQuantId()),
        "Move line belongs to another stock quant");

// 正常業務結果
if (availableBatches.isEmpty()) {
    return AllocationResult.outOfStock();
}

// Callee obligation
ensure(() -> pendingEvents.isEmpty(), "Pending events must be cleared");

// Aggregate responsibility
invariant(() -> !administrators.isEmpty(), "Team must have at least one administrator");
```

## 3. DbC、Validation、Domain Rule 與 Test

| 機制 | 回答的問題 | 失敗是否預期 | 常見處理 |
| --- | --- | --- | --- |
| Validation | 外部資料形狀可以接受嗎？ | 是 | HTTP 400、validation result |
| Domain Rule | 目前業務狀態允許操作嗎？ | 是 | Domain result／domain exception |
| `require` | Caller 是否正確使用 API？ | 否，代表 bug | Contract violation |
| `ensure` | Method 是否履行結果承諾？ | 否，代表 bug | Contract violation |
| `invariant` | Object 是否仍符合模型？ | 否，代表 bug 或壞資料 | Contract violation |
| Test | 特定情境與整合路徑是否符合預期？ | 否，代表 regression | Test failure |

快速判斷：

```text
外部輸入可能正常地錯？
    → Validation

業務狀態可能正常地拒絕操作？
    → Domain result / exception

Caller 在呼叫前能知道並且必須保證，違反代表程式寫錯？
    → require

Method 正常完成後必須保證的結果？
    → ensure

Object 完成公開操作後必須維持的狀態？
    → invariant
```

### 3.1 Validation 不是 `require`

外部輸入錯誤是正常事件：

```java
public record ChangeEmailRequest(
        @NotBlank
        @Email
        @Size(max = 254)
        String email) {}
```

Validation 應提供 client-friendly 4xx response。Contract violation 則表示內部程式錯誤，通常不應直接轉成
使用者的欄位錯誤。

相同 predicate 可以在不同邊界出現，但責任不同：

```text
REST：email 格式錯誤是使用者輸入錯誤
Domain：合法 EmailAddress 不得被建立成錯誤狀態
DbC：Application 已建立 EmailAddress，卻傳錯 User／context，是 caller bug
```

### 3.2 Domain Rule 不是 `require`

庫存不足、狀態不允許、資源不存在等情況，若是 use case 正常可能遇到的結果，就必須是 always-on Domain
behavior：

```java
if (availableToPromise() < quantity.value()) {
    throw new InsufficientStockException(
            "Available stock must cover requested quantity",
            quantity.value(),
            availableToPromise());
}
```

不要寫成可關閉的 Contract：

```java
require(
        () -> availableToPromise() >= quantity.value(),
        "Available stock must cover requested quantity");
```

### 3.3 Invariant monitoring 不是 invariant enforcement

Aggregate implementation 必須主動防止非法 transition：

```java
if (quantity.value() > reservedQuantity) {
    throw new InvalidConsumptionException(...);
}
```

完成操作後可再監測：

```java
invariant(
        () -> reservedQuantity >= 0
                && reservedQuantity <= onHandQuantity,
        "Reserved quantity must stay between zero and on-hand quantity");
```

`invariant()` 是偵測 implementation bug 的第二層 oracle，不是唯一保護。

## 4. 各架構層的責任

```text
HTTP / Message / CLI
    │
    │  格式、必填、長度
    ▼
Boundary Validation
    │
    │  primitive → Value Object / Command
    ▼
Application Handler
    │
    │  authorization、load、call、save、transaction
    ▼
Aggregate Root
    ├─ 少量 require：內部 caller obligation
    ├─ Always-on Domain decision / rejection
    ├─ State transition / Domain Event
    ├─ 少量 ensure：重要結果性質
    └─ 集中 invariant：Aggregate correctness
```

### 4.1 REST Request／Message DTO

負責：

- JSON、HTTP、message schema。
- 必填、字串長度、基本格式。
- Client-friendly validation message。

通常使用 Jakarta Validation，不需要 DbC。

### 4.2 Value Object／Command

Value Object 負責 intrinsic validity，例如 `EmailAddress`、`SkuCode`、`Quantity`、`DateRange`。非法 Value
Object 必須無法建立，不能只依賴可關閉 Contract：

```java
public record Quantity(int value) {

    public Quantity {
        if (value <= 0) {
            throw new InvalidQuantityException("Quantity must be positive", value);
        }
    }
}
```

Aggregate 接受 `Quantity` 後，不必再重複檢查 `value > 0`。

### 4.3 Application Service／Handler

Application Layer 負責：

- Authentication、authorization。
- 載入正確 Aggregate。
- Transaction boundary。
- 呼叫 Domain operation。
- 儲存 Aggregate、發布結果。
- Optimistic locking 與 infrastructure failure。

這些必要行為不得只做成可關閉 Contract。

Application Layer 可以有少量自己擁有的 orchestration contract，例如 handler 完成後回傳的 identity 必須
與 command 一致：

```java
public AllocateOrderResult handle(AllocateOrderCommand command) {
    Order order = orderStore.get(command.orderId());
    AllocateOrderResult result = order.allocate(command.lines());
    orderStore.save(order);

    ensure(
            () -> result.orderId().equals(command.orderId()),
            "Allocation result must belong to requested order");

    return result;
}
```

簡單、已有強型別保護的 handler 通常不需要 Contract。

### 4.4 Aggregate Root

Aggregate Root 同時擁有 Domain behavior 與 consistency boundary，是 `ensure`／`invariant` 最有價值的位置。
但純 `require` 通常很少，因為：

- DTO 已處理外部格式。
- Value Object 已排除 blank、negative 等非法內容，typed API 也減少直接傳遞 primitive。
- Application 已負責載入與 routing。
- 正常 business rejection 由 Aggregate 自己 always-on 處理。

純 `require` 主要偵測內部 caller 組裝錯誤，例如：

- Command identity 與已載入 Aggregate identity 不一致。
- Move line／event 屬於另一個 Aggregate。
- Owner、tenant 或 correlation context 傳錯。
- Internal helper 收到未依約正規化的資料。

若放過該條件可能造成 security 或資料完整性問題，即使理論上是 caller bug，也必須另有 always-on protection，
不能只靠可關閉 `require`。

## 5. Aggregate 中應有多少 Contract？

Contract 數量不由參數數量決定，而由重要責任與 state transition 複雜度決定。不過成熟設計通常呈現：

- 每個 operation 有 0～2 條純 `require`。
- 每個重要 state transition 有 0～3 條高價值 `ensure`。
- 多條共通 invariant 集中在一個 private method 或統一 lifecycle。

以上是可讀性 heuristic，不是 quota。

範例：

```java
public void receive(StockMoveLine line) {
    require(
            () -> id.equals(line.stockQuantId()),
            "Move line belongs to another stock quant");

    int oldOnHand = onHandQuantity;
    onHandQuantity = Math.addExact(onHandQuantity, line.quantity());

    ensure(
            () -> onHandQuantity == oldOnHand + line.quantity(),
            "Receiving stock must increase on-hand quantity");

    ensureQuantityInvariant();
}
```

若 wrong-quant line 可能從不可信 boundary 直接進入並造成錯帳，該 identity check 必須改成 always-on guard；此時
它不再只是 DbC monitoring。

### 5.1 控制 Contract 長度

不要替以下內容撰寫 Contract：

- Trivial getter。
- Value Object 已保證的 blank／positive 等 intrinsic validity。
- 每一行 assignment 的逐字重述。
- 與 implementation 完全相同、可能一起出錯的計算。

優先保留：

- 守恆關係。
- 跨欄位關係。
- Event 與 state mapping。
- 不應修改的關鍵狀態。
- Aggregate consistency invariant。

複雜條件可提取成有業務名稱的 pure private method：

```java
public void reserve(Quantity quantity) {
    rejectIfInsufficient(quantity);

    QuantitySnapshot before = snapshotQuantities();
    reservedQuantity += quantity.value();

    ensureReserveResult(before, quantity);
    ensureQuantityInvariant();
}
```

主流程應維持可辨識的結構：

```text
Caller assumption → Domain decision → Snapshot → Transition → Result check → Invariant
```

## 6. 為什麼 Contract 可以關閉？

真正的 DbC assertion 必須：

- Pure，沒有 side effect。
- 不決定正常 control flow。
- 不負責 Validation、Authorization 或 Domain enforcement。
- 在正確程式的合法執行中一定成立。

因此對正確程式：

```text
Contract ON  → 所有 predicate 都為 true
Contract OFF → 不計算 predicate
```

兩者的業務結果必須相同。關閉的是 bug monitoring，不是業務規則。

能關閉的常見理由是昂貴 Contract 的 production overhead，例如：

- 掃描大型 collection。
- 比較 old/new Aggregate snapshot。
- 驗證整份 event/state mapping。
- 在高頻 operation 重複計算 invariant。

「有完整測試」不是可關閉的根本原因；測試只是提升信心。未測路徑、concurrency、舊資料與新 caller 仍可能
在 production 暴露問題。

建議政策：

```text
Unit / Application / Integration / Acceptance Test
    → Contract 全開

CI
    → Contract 全開且禁止關閉

Production
    → 預設全開；有量測到的成本與明確風險評估後，才按 phase 關閉
```

## 7. DbC 與 Test 如何合作

```text
Test 提供執行情境與 coverage。
Contract 檢查該路徑上每次都必須成立的通用性質。
```

### 7.1 Aggregate Test

負責重要 Domain scenario 與 branch，例如：

- 有足夠庫存時 reserve。
- 庫存不足時拒絕。
- Release 不能超過 reserved。
- Idempotent command 的 changed／unchanged 結果。

執行時同時觸發 Aggregate 的 `require`、`ensure` 與 `invariant`。

### 7.2 Application／Use Case Test

負責 orchestration：

- 載入正確 Aggregate。
- 傳入正確 Value Object、identity、owner／tenant context。
- 呼叫正確 Domain operation。
- Save 與 publish 正確結果。

如果 Application 傳入另一個 Aggregate 的 line／event，Aggregate 的 `require` 可以在 use-case test 中立即指出
caller bug。

### 7.3 Integration／Acceptance Test

執行完整路徑：

```text
REST / Message
    → Application
    → Repository
    → Aggregate
    → Persistence / Event
```

Teddy／uContract 所強調的模式可理解為：Acceptance／Integration Test 驅動 production code，Contract 在各
元件附近充當 embedded oracle。Test 不必在最外層重複知道每一個內部欄位 assertion。

### 7.4 可以減少與不能減少的測試

可以減少重複內容：

- 每個測試逐欄檢查同一組 invariant。
- 每個測試重複檢查所有未修改欄位。
- 重複的 event/state mapping assertion。
- 重複的 version／defensive assertion。

不能刪除重要情境：

- Business branch 與 boundary value。
- Domain rejection。
- REST Validation、Authorization。
- Persistence、transaction rollback、optimistic locking。
- Concurrency。
- Event serialization、replay。
- Integration 與 E2E path。

Runtime DbC 不會探索路徑；沒有被 Test 或實際操作執行到的 branch，不會受到檢查。

## 8. DbC 對 Coding Agent 的價值

DbC 對 Agent 的價值不是單純「多一個 runtime assertion」，而是把規格放在 implementation 附近，成為可
執行限制：

```text
1. Agent 讀取 require / ensure / invariant。
2. 理解 caller assumption、允許修改範圍與預期結果。
3. 修改 implementation。
4. 執行 Unit / Application / Integration Test。
5. Test 觸發 Contract。
6. Violation 的 phase、message 與 cause 協助定位責任。
7. Agent 修正並重新驗證。
```

價值包括：

- 縮小可接受 implementation 的範圍。
- 更早指出錯誤屬於 caller 或 callee。
- 降低誤改無關狀態的風險。
- 讓測試聚焦 business scenario。

Contract 過多也會傷害 Agent：主流程被低價值 assertion 淹沒，或同一規則在多層重複，都會增加推理與維護
成本。因此應追求高資訊密度，不是高數量。

## 9. uContract 與其他相鄰 API

### 9.1 如何解讀 Teddy／uContract 的 `require`

Teddy 的公開文章與 Root Contracting 範例，會把 non-null、non-empty、長度限制、Aggregate lookup 與部分
business constraint 都列為 precondition。這在經典 DbC 中可以成立，前提是：

- Caller 有能力在呼叫前知道並滿足條件。
- Boundary／Application 已處理可能正常發生的輸入錯誤。
- 違反 precondition 一律視為 caller bug，而不是 Domain 的正常失敗結果。

這些材料的主要目標是用 Contract 描述 event-sourced Aggregate 行為，沒有完整展開 REST Validation、Domain
rejection 與 Application error mapping。直接照抄範例而沒有補上這些層次，會使 `DBC_PRE=off` 同時移除實際
業務保護。

本專案因此採用較窄的 `require` 定義：只有純 caller programmer obligation 使用 `require`；正常可能發生的
Validation／Domain failure 一律 always-on。這是採用政策上的收斂，不代表 uContract 的經典 DbC 解讀在理論
上錯誤。

### 9.2 為什麼只保留 `Contract`

若同一個專案除了 DbC 又提供另一組名稱不同、但同樣接受 condition 與 message 的 assertion API，開發者必須在
每次檢查時先選擇兩套平行語言：

```java
Contract.require(() -> condition, "Caller must satisfy the operation precondition");
Contract.ensure(() -> condition, "Operation must produce the promised result");
```

再另外選擇 always-on defensive check。這會把 enforcement policy 與 contract responsibility 兩個分類維度混在
一起，增加 API 選擇成本。

本手冊的團隊預設是：

- DbC 只使用 `Contract.require()`、`Contract.ensure()`、`Contract.invariant()`。
- REST constraint 使用 Jakarta Validation。
- Value Object validity 使用 constructor／factory。
- Normal Domain rejection 使用 Domain result／exception。
- Java 基本防禦使用 `Objects.requireNonNull()` 或明確 `if/throw`。
- Repository／dependency 資料錯誤使用有語意的 infrastructure exception。

不要在 `Contract` 中再加入 `requireAlways()`，也不要用 `checkNotBlank()`、`checkNotEmpty()` 等 convenience
method 擴張成另一套 Validation framework。

`domain-contract` 因此只保留 `Contract` 作為 contract 撰寫入口。Phase-specific exception 與
`ContractViolationException` 是執行期及 Global Exception Handler 所需的支援型別，不是另一套撰寫 API。

## 10. Contract 撰寫準則

### 10.1 先選對責任與 phase

不要因為「想檢查一個 boolean」就使用 Contract。先判斷失敗責任屬於 caller、callee、Aggregate，還是正常
Domain rejection。

反例：把正常可能發生的庫存不足寫成可關閉的 `require`：

```java
require(
        () -> availableToPromise() >= quantity.value(),
        "Available stock must cover requested quantity");
```

正例：正常業務拒絕 always-on；只有 internal caller 傳錯 Aggregate 才用 `require`：

```java
require(
        () -> id.equals(request.stockQuantId()),
        "Reservation request belongs to another stock quant");

if (availableToPromise() < quantity.value()) {
    throw new InsufficientStockException(
            "Available stock must cover requested quantity",
            quantity.value(),
            availableToPromise());
}
```

結果由 method 負責時使用 `ensure`，Aggregate 一直必須成立的關係使用 `invariant`：

```java
ensure(
        () -> reservedQuantity == oldReservedQuantity + quantity.value(),
        "Reserving stock must increase reserved quantity by the requested amount");

invariant(
        () -> reservedQuantity >= 0 && reservedQuantity <= onHandQuantity,
        "Reserved quantity must stay between zero and on-hand quantity");
```

### 10.2 Predicate 必須 pure、deterministic，而且只讀記憶體狀態

反例：Contract 內修改狀態或呼叫外部 dependency：

```java
ensure(() -> events.add(new OrderAllocated(orderId)), "Order allocation event must be appended");

ensure(
        () -> repository.findById(orderId).isPresent(),
        "Saved order must be visible in the repository");
```

關閉 Contract 後，第一個例子的業務結果會改變；第二個例子則引入 IO、延遲與不穩定性。

正例：method body 完成 side effect，Contract 只讀取已存在的結果：

```java
OrderAllocated event = new OrderAllocated(orderId);
events.add(event);

ensure(
        () -> events.contains(event),
        "Order allocation event must be appended");
```

### 10.3 Old state 必須在 mutation 前保存

反例：mutation 後才取得「舊值」，結果變成 tautology：

```java
reservedQuantity += quantity.value();
int oldReservedQuantity = reservedQuantity;

ensure(
        () -> reservedQuantity == oldReservedQuantity,
        "Reserving stock must increase reserved quantity by the requested amount");
```

正例：在 state transition 前保存 primitive 或 immutable snapshot：

```java
int oldReservedQuantity = reservedQuantity;

reservedQuantity += quantity.value();

ensure(
        () -> reservedQuantity == oldReservedQuantity + quantity.value(),
        "Reserving stock must increase reserved quantity by the requested amount");
```

若需要多個欄位，使用有名稱的 immutable snapshot：

```java
QuantitySnapshot before = new QuantitySnapshot(onHandQuantity, reservedQuantity);

applyReservation(quantity);

ensure(
        () -> onHandQuantity == before.onHand(),
        "Reserving stock must not change on-hand quantity");
```

### 10.4 避免 tautology 與共用同一個錯誤來源

反例：自己和自己比較，永遠不會失敗：

```java
ensure(
        () -> state.email().equals(state.email()),
        "User email must change to the requested address");
```

反例：production calculation 與 Contract 使用同一個可能有 bug 的 helper：

```java
total = calculateTotal(lines);

ensure(
        () -> total.equals(calculateTotal(lines)),
        "Order total must match its lines");
```

正例：與輸入、舊狀態或獨立性質比較：

```java
ensure(
        () -> state.email().equals(newEmail),
        "User email must change to the requested address");

ensure(
        () -> source.balance().add(target.balance()).equals(oldTotal),
        "Money transfer must conserve the total balance");
```

精確結果公式本身就是規格時可以直接寫；重點是不要讓 implementation 與 Contract 共享同一段錯誤計算。

### 10.5 不要重複 Value Object 已保證的條件

反例：Aggregate 每個 operation 都重複 primitive validation：

```java
public void reserve(Quantity quantity) {
    require(() -> quantity.value() > 0, "Quantity must be positive");
    // ...
}
```

正例：`Quantity` 在建立時 always-on 維持 intrinsic validity，Aggregate 只關心自己的責任：

```java
public record Quantity(int value) {

    public Quantity {
        if (value <= 0) {
            throw new InvalidQuantityException("Quantity must be positive", value);
        }
    }
}

public void reserve(StockQuantId requestedId, Quantity quantity) {
    require(
            () -> id.equals(requestedId),
            "Reservation request belongs to another stock quant");
    // ...
}
```

Value Object 能保證自身內容，不能保證 Java reference 一定不為 `null`。若 public API 仍可能收到 `null`，應依
API policy 使用 `Objects.requireNonNull()`、nullness tooling，或將它視為明確的 caller contract；不要把
「reference 不為 null」和「Value Object 內容合法」混為一談。

### 10.6 使用簡短、固定的開發診斷訊息

反例：每次呼叫都組合動態訊息：

```java
ensure(
        () -> reservedQuantity <= onHandQuantity,
        "Reserved quantity " + reservedQuantity
                + " exceeds on-hand quantity " + onHandQuantity);
```

即使條件成立或 Contract 已關閉，method arguments 仍會先完成字串組合。動態值也可能使 log grouping 困難，
並增加敏感資料外洩風險。

正例：使用固定、能配合 source line 與 stack trace 理解的 plain message：

```java
ensure(
        () -> reservedQuantity <= onHandQuantity,
        "Reserved quantity must not exceed on-hand quantity");
```

Request、tenant、trace 等動態 context 交給 logging／tracing。Contract message 只供內部診斷，不是終端使用者
訊息，也不需要自行承擔完整 observability context。

### 10.7 集中共通 invariant，避免淹沒主流程

反例：每個 operation 都展開同一組低階條件：

```java
invariant(() -> onHandQuantity >= 0, "On-hand quantity must not be negative");
invariant(() -> reservedQuantity >= 0, "Reserved quantity must not be negative");
invariant(() -> reservedQuantity <= onHandQuantity, "Reserved quantity must not exceed on-hand quantity");
invariant(() -> version >= 0, "Version must not be negative");
```

正例：集中到有語意的 pure private method：

```java
private void ensureQuantityInvariant() {
    invariant(
            () -> onHandQuantity >= 0
                    && reservedQuantity >= 0
                    && reservedQuantity <= onHandQuantity,
            "Stock quantities must remain in a valid range");
}
```

公開 operation 只在 state transition 完成後呼叫：

```java
public void release(Quantity quantity) {
    rejectIfReleaseExceedsReserved(quantity);
    reservedQuantity -= quantity.value();
    ensureQuantityInvariant();
}
```

若某一條 invariant 需要不同的診斷訊息、昂貴計算或不同關閉政策，可以保留成獨立檢查，不必為了減少行數
強行合併。

### 10.8 Contract 要驗證重要性質，不要逐行翻譯 implementation

反例：每個 assignment 都附上一條低價值 assertion：

```java
status = Status.ALLOCATED;
ensure(() -> status == Status.ALLOCATED, "Order status must become allocated");

updatedAt = clock.instant();
ensure(() -> updatedAt != null, "Order update time must be recorded");
```

正例：檢查跨欄位、守恆或 event/state mapping：

```java
ensure(
        () -> status == Status.ALLOCATED
                && allocatedLines.size() == requestedLines.size()
                && lastEvent() instanceof OrderAllocated,
        "Order allocation state and event must remain consistent");
```

若單一 assignment 本身就是高風險業務承諾，例如 security state、money ownership 或 irreversible transition，
仍可保留明確 Contract；判斷標準是資訊價值，不是程式碼行數。

### 10.9 維持 fail-fast

第一個 Contract failure 應立即拋出 exception。後續 predicate 可能依賴前一條已成立：

```java
ensure(() -> result != null, "Allocation result must be present");
ensure(
        () -> result.orderId().equals(orderId),
        "Allocation result must belong to the requested order");
```

若收集成 soft assertions，第一條失敗後仍執行第二條，可能只得到額外的 `NullPointerException`，模糊真正原因。
Soft Contract 只適合多個互不依賴、純診斷用途的條件，且應設計成另一個明確 API。

### 10.10 完整 Aggregate 範例

```java
public void reserve(ReservationRequest request, Quantity quantity) {
    require(
            () -> id.equals(request.stockQuantId()),
            "Reservation request belongs to another stock quant");

    if (availableToPromise() < quantity.value()) {
        throw new InsufficientStockException(
                "Available stock must cover requested quantity",
                quantity.value(),
                availableToPromise());
    }

    int oldOnHandQuantity = onHandQuantity;
    int oldReservedQuantity = reservedQuantity;

    reservedQuantity += quantity.value();
    pendingEvents.add(new StockReserved(id, quantity));

    ensure(
            () -> reservedQuantity == oldReservedQuantity + quantity.value(),
            "Reserving stock must increase reserved quantity by the requested amount");
    ensure(
            () -> onHandQuantity == oldOnHandQuantity,
            "Reserving stock must not change on-hand quantity");
    ensure(
            () -> lastEvent() instanceof StockReserved event
                    && event.stockQuantId().equals(id)
                    && event.quantity().equals(quantity),
            "Stock reserved event must match the resulting aggregate state");

    ensureQuantityInvariant();
}
```

這個範例刻意維持清楚順序：

```text
Caller obligation → Domain rejection → Snapshot → State transition → Postconditions → Invariant
```

## 11. 專案 Lambda DSL 與開關

### 11.1 DSL、狀態保存與開關

Consumer module：

```groovy
implementation project(':domain-contract')
```

Static import：

```java
import static com.flowzati.archone.contract.Contract.ensure;
import static com.flowzati.archone.contract.Contract.invariant;
import static com.flowzati.archone.contract.Contract.require;
```

Condition 放前面，plain diagnostic message 放後面：

```java
ensure(
        () -> reservedQuantity == oldReservedQuantity + quantity,
        "Reserving stock must increase reserved quantity by the requested amount");
```

目前 API 不提供 `old()`、deep copy 或 `ensureAssignable()`；需要 old state 時明確保存 primitive 或 immutable
snapshot：

```java
int oldReservedQuantity = reservedQuantity;
```

`Contract` class 初始化時讀取：

| 環境變數 | 效果 |
| --- | --- |
| `DBC=off` | 關閉全部 Contract monitoring |
| `DBC_PRE=off` | 關閉 `require` |
| `DBC_POST=off` | 關閉 `ensure` |
| `DBC_INV=off` | 關閉 `invariant` |

開關是 process-wide 啟動設定，不是 request-scoped feature flag。停用時不執行 predicate。內部使用
`ThreadLocal` 避免 predicate 評估期間重入 Contract。

Exception 保留：

- Contract phase。
- Plain diagnostic message。
- Predicate evaluation cause。

詳細 API 見 [`backend/domain-contract/README.md`](../backend/domain-contract/README.md)。

### 11.2 Global Exception Handler

`ContractViolationException` 應一路向外傳播，讓 transaction rollback，再由最外層 delivery mechanism 統一處理。
REST API 的建議流程是：

```text
Aggregate / Application
    → throw ContractViolationException
    → transaction rollback
    → Global Exception Handler
        ├─ ERROR log：incidentId、phase、message、stack trace
        └─ HTTP 500：通用訊息與 incidentId
```

公開 response 不應包含 Contract 的 `phase`、diagnostic message、predicate cause 或 stack trace。
這些是內部診斷資料，不是使用者可修正的欄位錯誤。

專案既有的 `GlobalRestExceptionHandler` 已加入以下處理：

```java
// imports omitted
@RestControllerAdvice
public class GlobalRestExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(GlobalRestExceptionHandler.class);
    private static final URI CONTRACT_VIOLATION_TYPE =
            URI.create("urn:archone:problem:internal-contract-violation");
    private static final String INTERNAL_ERROR_CODE = "INTERNAL_ERROR";
    private static final String INTERNAL_ERROR_TITLE = "Internal server error";
    private static final String INTERNAL_ERROR_DETAIL =
            "The system could not complete the request";

    @ExceptionHandler(ContractViolationException.class)
    public ResponseEntity<ProblemDetail> handleContractViolation(
            ContractViolationException exception,
            HttpServletRequest request) {
        String incidentId = UUID.randomUUID().toString();

        LOGGER.error(
                "Contract violation: incidentId={}, phase={}, message={}",
                incidentId,
                exception.phase(),
                exception.getMessage(),
                exception);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                INTERNAL_ERROR_DETAIL);
        problem.setType(CONTRACT_VIOLATION_TYPE);
        problem.setTitle(INTERNAL_ERROR_TITLE);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", INTERNAL_ERROR_CODE);
        problem.setProperty("incidentId", incidentId);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }
}
```

公開 response 範例：

```json
{
  "type": "urn:archone:problem:internal-contract-violation",
  "title": "Internal server error",
  "status": 500,
  "detail": "The system could not complete the request",
  "instance": "/stock-quants/4f91/reservations",
  "code": "INTERNAL_ERROR",
  "incidentId": "4f04d073-9f04-4661-af23-692072d8fda9"
}
```

內部 log 則應能用同一個 `incidentId` 找到：

```text
Contract violation: incidentId=4f04d073-9f04-4661-af23-692072d8fda9,
phase=INVARIANT,
message=Reserved quantity must stay between zero and on-hand quantity
```

處理時遵守以下原則：

- `PreconditionViolationException` 也是 `500`，不是 `400`；依本手冊定義，它表示內部 caller bug。
- Global handler 不應把 Contract exception 轉成成功 response、fallback result 或正常 Domain rejection。
- 避免在 Controller 用泛用的 `@ExceptionHandler(Exception.class)` 一律回 `400`，否則可能先攔走 Contract
  violation；應保留明確的 exception taxonomy。
- Public response 使用固定的通用訊息，不回傳 Contract 的 diagnostic message。
- Contract message 使用固定文字，不應拼接 password、token、未遮罩個資或其他動態 request context。
- `spring-framework-support` 以 `api project(':domain-contract')` 取得公開 handler method 使用的 exception type；
  `domain-contract` 仍維持 framework-neutral，不反向依賴 Spring。
- 若 tracing 系統已有 trace ID，可以在 response 與 log 中使用 trace ID，或將它和 incident ID 一起記錄；重點是
  client 提供的識別碼必須能查回同一筆錯誤。
- Message consumer 沒有 HTTP response 時採相同分類：記錄完整 violation、觸發 retry／dead-letter policy 與告警，
  不把它當作一般 invalid message 靜默略過。

Exception 對外分類建議：

| 來源 | HTTP | Public response | Internal diagnostic |
| --- | --- | --- | --- |
| Request Validation | `400` | 欄位與 constraint 訊息 | Validation details |
| Normal Domain rejection | `409`／`422` | 可採取行動的業務訊息 | Domain code 與 context |
| Contract violation | `500` | 通用訊息與 incident ID | Phase、message、cause、stack trace |
| 其他未預期 exception | `500` | 通用訊息與 incident ID | Exception type、cause、stack trace |

測試分成兩個層次。Domain test 驗證精確 violation，例如 internal caller 傳入另一個 Aggregate 的 request：

```java
ReservationRequest requestForAnotherQuant =
        new ReservationRequest(anotherStockQuantId, reservationId);

PreconditionViolationException exception = assertThrows(
        PreconditionViolationException.class,
        () -> stockQuant.reserve(requestForAnotherQuant, quantity));

assertThat(exception)
        .hasMessage("Reservation request belongs to another stock quant");
```

REST test 可安排 mocked Application Service 拋出 Contract exception，只驗證安全的公開格式，不應期待
Contract message 外洩：

```java
mockMvc.perform(post("/stock-quants/{id}/reservations", stockQuantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        .andExpect(jsonPath("$.incidentId").isNotEmpty())
        .andExpect(jsonPath("$.message").doesNotExist());
```

## 12. 開發與 Review 檢查表

- [ ] 這條錯誤是外部輸入錯誤、正常 Domain rejection，還是 programmer bug？
- [ ] DTO 是否處理外部格式 Validation？
- [ ] Primitive 是否儘早轉成 Value Object？
- [ ] Value Object 是否 always-on 阻止非法狀態？
- [ ] 正常業務拒絕是否使用 Domain result／exception？
- [ ] `require` 是否真的是 caller 可事先知道並必須保證的條件？
- [ ] 安全與資料完整性條件是否另有 always-on protection？
- [ ] `ensure` 是否描述重要結果，而非逐字重述 assignment？
- [ ] Aggregate invariant 是否集中檢查，且 implementation 本身主動維持？
- [ ] Predicate 是否 pure、type-safe、沒有 IO？
- [ ] 是否使用簡短、固定且不含敏感動態資料的 diagnostic message？
- [ ] Global handler 是否將 Contract violation 回傳為安全的通用 `500`，並保留可追查的 incident ID？
- [ ] Public response 是否沒有洩漏 Contract message、cause 或 stack trace？
- [ ] Contract 是否讓主流程更清楚，而不是被 assertion 淹沒？
- [ ] Aggregate、Application、Integration Test 是否會執行相關路徑？
- [ ] 重要 business branch、concurrency、persistence 是否仍有測試？
- [ ] 關閉 DbC 後，合法／非法業務結果、安全與資料完整性是否完全不變？

## 13. 最終心智模型

```text
Validation
    保護外部輸入邊界

Value Object / Domain Model
    Always-on 保護業務狀態與正常 Domain behavior

Design by Contract
    監測 caller、callee、object 是否履行設計承諾

Test
    產生情境與路徑，觸發 Contract，驗證外部可觀察行為與整合
```

最終原則：

> Test 驅動路徑，Contract 監測承諾，Domain Model 真正維持正確性。任何可關閉的 DbC 都不得成為安全、
> Validation、正常業務拒絕或資料完整性的唯一防線。

## 14. 參考資料

- [uContract README](https://gitlab.com/TeddyChen/ucontract/-/blob/main/README.md)
- [Root Contracting demo](https://gitlab.com/TeddyChen/root-contracting)
- [Teddy：用合約彌補測試案例的不足](https://teddy-chen-tw.blogspot.com/2021/03/)
- [Eiffel：Design by Contract、Assertions and Exceptions](https://www.eiffel.org/doc/eiffel/ET-_Design_by_Contract_%28tm%29%2C_Assertions_and_Exceptions)
- [Oracle：Programming With Assertions](https://docs.oracle.com/javase/8/docs/technotes/guides/language/assert.html)
