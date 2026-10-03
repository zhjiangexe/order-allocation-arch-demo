# Archone 文件入口

本目錄只保存無法從程式碼可靠還原、而且仍會影響設計或維運的現行知識。歷史提案、完成紀錄、一次性驗證結果與
實作導讀不留在工作樹；需要追溯時使用 Git history。

## Source of truth

| 問題 | 主要依據 |
| --- | --- |
| 系統現在實際做什麼 | production code、database migration 與 automated tests |
| 為什麼這樣切邊界、哪些限制不能破壞 | 本目錄的 DDD、architecture 與 decision 文件 |
| 如何建置、啟動、設定或操作單一模組 | repository／module README、可執行 script 與 runbook |
| 過去如何演進 | Git history |
| symbol、caller 與依賴關係 | 程式碼搜尋與可重建的 Graphify graph |

文件與程式碼不一致時，不直接假定任一方正確：先用測試與執行路徑確認 AS-IS，再判斷是文件過期或實作偏離決策。

## 文件地圖

| 文件 | 回答的問題 |
| --- | --- |
| [3PL 策略](ddd/strategy.md) | 產品目前是什麼、目標往哪裡走、刻意不做什麼 |
| [Context map](ddd/context-map.md) | 各 bounded context 擁有什麼，以及如何合作 |
| [Ubiquitous language](ddd/ubiquitous-language.md) | 相同詞彙在各 context 的精確意思 |
| [決策與待決問題](ddd/decisions.md) | 已定案的取捨、演進條件與仍需決定的事項 |
| [C4 Model](architecture/c4-model.md) | 系統、container、component、deployment 與 Current／Target 邊界 |
| [Quality attributes](architecture/quality-attributes.md) | 品質目標、可驗收 scenarios、證據與尚未量化的缺口 |
| [Inventory 架構](architecture/inventory.md) | 庫存真相、搬運、配貨與一致性規則 |
| [Fulfillment orchestration](architecture/fulfillment-orchestration.md) | Events／Temporal 如何協調 Ordering、Inventory 與 WMS |
| [Architecture Decision Records](adr/README.md) | 架構重大決策的背景、選項、後果、驗證與重開條件 |
| [Messaging 維運](operations/messaging.md) | Outbox、Inbox、Debezium、Kafka 的維運與事故處理 |
| [Design by Contract](development/design-by-contract.md) | 專案使用 DbC 的責任切分與 review 規則 |

## 撰寫規則

- 文件必須標示 `Current`、`Target` 或 `Open`；不得把未實作方向寫成現況。
- 只連結讀者確實需要開啟的穩定入口，不維護 class、adapter、repository 的人工索引。
- 業務規則應由測試保護；文件只說規則、理由與責任邊界，不複製完整 test scenarios。
- 不保存已完成 roadmap、舊 package tree、部署驗收截圖、產生後的 PNG 或一次性 benchmark。
- 架構圖優先用鄰近文件的可維護 Mermaid／PlantUML source；產生物不進 `docs/`。
- 重大 rename、module move 或文件改組後，要同步修正 README／JavaDoc 連結並重建 Graphify。

Graphify 是導航用的衍生資料，不是規範來源；`graphify-out/` 可隨時由 repository 重建。
