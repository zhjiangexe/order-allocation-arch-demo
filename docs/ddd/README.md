# Archone DDD 戰略設計文件

狀態：第一輪策略討論已確認；實作仍以各文件的 AS-IS／TO-BE 標記為準
盤點基準：2026-09-24

## 文件目的

這組文件用來建立 Archone 的共同業務語言、bounded context 邊界與跨 context 合作方式。內容以現行程式碼與
近期架構文件為 AS-IS 證據；尚未能由程式碼證實的分類或目標設計會明確標為「假設」或「待確認」。

這些文件不取代 class-level 設計、API 文件或操作手冊，也不把 Gradle module 直接等同 bounded context。

## 閱讀順序

| 文件 | 回答的問題 |
| --- | --- |
| [domain-vision.md](domain-vision.md) | 系統為誰解決什麼問題，邊界到哪裡？ |
| [strategic-decisions.md](strategic-decisions.md) | 這輪討論已經做了哪些定位與治理決策？ |
| [industry-positioning.md](industry-positioning.md) | 主流 WMS／OMS／3PL 產品的公開模型如何支持此定位？ |
| [subdomain-map.md](subdomain-map.md) | 哪些能力值得差異化投資？ |
| [domain-narrative.md](domain-narrative.md) | 從收單到離倉，業務事實如何推進？ |
| [bounded-contexts.md](bounded-contexts.md) | 每個模型邊界擁有什麼、不擁有什麼？ |
| [context-map.md](context-map.md) | Context 之間如何合作與交換事實？ |
| [ubiquitous-language.md](ubiquitous-language.md) | 關鍵詞在各 context 中代表什麼？ |
| [integration-events.md](integration-events.md) | 現行跨 context 事件契約有哪些？ |
| [open-questions.md](open-questions.md) | 哪些策略判斷仍需要業務決策？ |

## 證據標記

- **已確認（code）**：可由 production code、contract 或 migration 直接證實。
- **已記錄（docs）**：存在於既有設計文件，但可能尚未完全落地或已與程式碼漂移。
- **假設**：根據命名、流程與現況推導，尚未由 domain expert 確認。
- **TO-BE 建議**：未經決策，不代表現行架構。
- **TO-BE 已決策**：策略方向已確認，但不表示程式碼已落地。

## 維護原則

1. Business capability、bounded context、module 與 deployment 是四種不同邊界。
2. Context Map 只描述模型所有權與合作關係，不把 Kafka、Outbox、JDBC 或 Temporal 當成領域。
3. Application Event 使用 context 內語言；Integration Event 使用跨 context 的 published language。
4. 調整 context 邊界時，同步更新本目錄、相關 architecture rule 與 integration contract。
5. 舊分析文件若與現行實作不同，保留歷史理由，但由本目錄明確標示 AS-IS 與待決策差異。

歷史文件的分類與原始內容索引見 [Archived Documentation](../archive/README.md)。
文件與 implementation 關聯的維護方式見 [文件與程式碼關聯政策](../documentation-reference-policy.md)。
