# 文件與程式碼關聯政策

狀態：現行文件治理規則
更新日期：2026-09-25

## 目的

人工維護的文件負責穩定的業務語意、架構決策與操作契約；Graphify 負責從當前 repository 衍生容易隨重構
改變的 implementation 關聯。文件不應複製一份必須人工同步的程式碼索引。

## Source of truth

| 問題 | 主要依據 |
| --- | --- |
| 為什麼存在、業務邊界與責任歸屬 | `docs/ddd/`、ADR、architecture 文件 |
| 系統必須符合的行為 | `openspec/specs/` 的 requirement 與 scenario |
| 如何建置、啟動、設定或操作 | 鄰近 module 的 README、runbook |
| 現在哪個 symbol 實作、呼叫或依賴某能力 | production code、tests、Graphify |
| 過去如何演進與驗證 | `docs/archive/`、`openspec/changes/archive/` |

若文件、Graphify 與 repository 不一致，implementation 事實以 production code 與 tests 為準；業務規則則應先釐清
是程式碼漂移，還是 requirement 已經改變，不能只用圖譜結果覆寫規格。

## 何時保留直接路徑

只在讀者必須直接開啟或操作該檔案時保留路徑，例如：

- published contract、migration 或 deploy configuration；
- 驗收用的關鍵 automated test；
- module README、runbook 或可執行 script；
- 路徑本身就是工具或 build contract 的一部分。

優先連到 module、公開介面或少數穩定入口。不要在策略文件中列出所有 implementation class、caller、adapter、
repository 或 package tree，也不要使用行號作為長期引用。

## OpenSpec trace

主規格的 `@trace` 只保留 requirement 的歷史來源與同步日期：

```yaml
<!-- @trace
source: change-name
updated: YYYY-MM-DD
-->
```

完整的實作與測試證據屬於對應的 archived change。不要把 change 的完整 `code:`／`tests:` 清單複製到每條
requirement；這種清單會重複、快速失效，也會讓規格難以閱讀。若某個檔案本身就是 requirement 的一部分，應在
requirement 或 scenario 中說明原因，而不是混入自動產生的完整檔案清單。

## Graphify 的角色

Graphify 是 derived navigation，不是 normative specification。適合用來回答：

```text
graphify query "What implements stock allocation commitment?"
graphify affected "OrderFulfillmentWorkflow"
graphify explain "Inventory"
```

使用結果時應區分 `EXTRACTED`、`INFERRED` 與 `AMBIGUOUS` 關係。重要驗收或架構判斷仍須回到程式碼、測試、
contract 或正式決策文件確認。

`graphify-out/` 是可重建的本機產物，不納入 Git。團隊共享的是這份治理規則、canonical documents 與建圖所需的
repository 內容，不共享可能過期的大型 `graph.json`。

重大 rename、module move、文件改組或大量刪除後必須更新或重建 graph。若 graph 尚未更新，查詢結果只能視為
導航線索，不得作為現況證據。
