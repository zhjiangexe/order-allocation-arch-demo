# Design by Contract development policy

更新日期：2026-09-26

專案的 DbC runtime 位於 `backend/archone-framework/domain-contract`。API、環境變數與 exception 形狀以該 module 的
README 和 tests 為準；本文件只規範何時使用及如何 review。

## Responsibility model

| Mechanism | 問題 | 必須永遠啟用？ |
| --- | --- | --- |
| Input validation | 外部 request／message 是否完整、格式是否合法 | 是 |
| Authorization／policy | caller 是否允許執行、業務方案是否可接受 | 是 |
| Domain rule | 正常業務狀態是否允許這次 transition | 是 |
| Precondition | 內部 caller 是否履行方法契約 | 可關閉 |
| Postcondition | method body 是否產生承諾的結果 | 可關閉 |
| Invariant | 物件完成 mutation 後是否仍符合重要一致性 | 可關閉 |
| Automated test | 跨範例、transaction、integration 與 regression 是否成立 | 不可由 Contract 取代 |

可關閉的 Contract 只適合偵測 programmer error。任何在 production 關閉後仍必須保護資料、權限或正常業務拒絕的
條件，都必須由 always-on code enforce，不能只放進 `require`／`ensure`／`invariant`。

## Placement

- REST／message DTO：做 transport validation，轉成 normalized command。
- Value object／command：在建構時維護 always-on structural validity。
- Application use case：檢查 authorization、business preconditions、transaction 與 external coordination。
- Aggregate／entity：執行 state transition 與 always-on domain rules；Contract 補充 caller responsibility、
  transition result 與關鍵 invariant。
- Infrastructure：不得用 Domain Contract 掩飾 database、network 或 serialization failure。

## Contract rules

1. Predicate 必須 pure、deterministic、只讀記憶體狀態；不得寫 database、呼叫外部服務、發布 event 或修改物件。
2. Mutation 前明確保存 primitive／immutable old state；不要以 mutable reference 假裝是舊值。
3. Contract 驗證重要性質，不逐行翻譯 implementation，也不重複 value object 已經保證的條件。
4. Predicate 與 implementation 不得共用同一個錯誤計算來源，否則容易形成 tautology。
5. Diagnostic message 短、固定且只供開發者；request、tenant、identity 與 trace context 由 logging／tracing 提供。
6. 採 fail-fast；不要依賴同一 phase 收集所有 violations。
7. 共通 invariant 可以集中成 private method，但要維持可讀性，避免每個 mutation 被大量低價值 checks 淹沒。
8. Contract failure 對外回傳固定通用錯誤與 incident ID，不暴露 predicate message 或 evaluation cause。

## Example

```java
public void reserve(int quantity) {
    if (quantity <= 0) {
        throw new IllegalArgumentException("Quantity must be positive");
    }
    if (availableToPromise() < quantity) {
        throw new IllegalStateException("Insufficient ATP");
    }

    int oldOnHand = onHandQuantity;
    int oldReserved = reservedQuantity;
    reservedQuantity += quantity;

    ensure(
            () -> reservedQuantity == oldReserved + quantity,
            "Reservation must increase by the requested quantity");
    ensure(() -> onHandQuantity == oldOnHand, "Reservation must not change on-hand quantity");
    invariant(() -> reservedQuantity <= onHandQuantity, "Reserved quantity must not exceed on-hand quantity");
}
```

正數與 ATP 是正常業務安全條件，因此 always-on；後三個 checks 用來驗證 implementation 是否履行責任。

## Testing

- Aggregate tests 覆蓋合法 transition、正常 rejection、idempotency 與 invariant boundary。
- Use-case tests 覆蓋 authorization、transaction、publication 與 collaborator outcomes。
- Integration tests 覆蓋 database constraint、rollback、concurrency 與 external adapter mapping。
- Contract tests只驗證 DSL、phase、開關、fail-fast 與 exception diagnostics。
- 不因加了 Contract 就刪除上述測試；只有完全重複 implementation detail 的 assertion 才能在 review 後移除。

## Review checklist

- 這是 programmer error，還是正常 business／input rejection？
- 關閉 Contract 後，必要的資料與安全規則是否仍成立？
- 條件是否放在真正擁有責任的 layer／method？
- Predicate 是否 pure、bounded，且不會觸發 lazy I/O？
- Old state 是否在 mutation 前以 immutable value 保存？
- Postcondition 是否驗證 observable property，而不是照抄 implementation？
- 訊息是否穩定且不包含 PII／secret／高基數資料？
- Unit、application 與 integration tests 是否仍覆蓋失敗與 transaction behavior？
