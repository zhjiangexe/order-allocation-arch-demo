# Archone 文件入口

> Current · 開發、操作與架構文件的導覽入口。

[回到專案首頁](../README.md)

本目錄保存現行操作指南、設計理由與責任邊界。歷史提案、完成紀錄與一次性驗證結果使用 Git history 追溯。

## 開始使用

| 想做什麼 | 文件 |
| --- | --- |
| 啟動含前端與示範資料的系統 | [示範系統操作指南](operations/demo.md) |
| 在主機或 IDE 修改前後端 | [本機開發](development/local-development.md) |
| 選擇測試範圍、查看報告 | [測試與檢查](development/testing.md) |
| 打包與啟動 Monolith／Microservice | [打包與部署](operations/deployment.md) |
| 建立 schema、執行 migration | [資料庫流程](../database/README.md) |
| 了解操作台欄位、收貨與履約追蹤 | [前端操作說明](../frontend/README.md) |

## Source of truth

| 問題 | 主要依據 |
| --- | --- |
| 系統現在實際做什麼 | production code、database migration 與 automated tests |
| 為什麼這樣切邊界、哪些限制不能破壞 | 本目錄的 DDD、architecture 與 decision 文件 |
| 如何建置、啟動、設定或操作 | 本目錄的操作指南、module README 與可執行 script |
| 過去如何演進 | Git history |
| symbol、caller 與依賴關係 | 程式碼搜尋與可重建的 Graphify graph |

文件與程式碼不一致時，不直接假定任一方正確：先用測試與執行路徑確認 AS-IS，再判斷是文件過期或實作偏離決策。

## 架構與設計

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
- 不保存已完成 roadmap、舊 package tree、一次性部署驗收截圖或 benchmark。
- 首頁展示用的實際產品截圖放在 `docs/images/`；更新 UI 後一併更新圖片，不放入真實客戶資料。
- 架構圖優先用鄰近文件的可維護 Mermaid／PlantUML source，避免另外保存可重建的圖片。
- 重大 rename、module move 或文件改組後，要同步修正 README／JavaDoc 連結並重建 Graphify。

Graphify 是導航用的衍生資料，不是規範來源；`graphify-out/` 可隨時由 repository 重建。
