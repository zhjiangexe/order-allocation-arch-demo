# Archone Framework

此目錄集中不屬於任何 bounded context 的共用技術模組：

- `foundation`：共用錯誤、識別碼、時間與模擬工具。
- `domain-contract`：framework-neutral Design by Contract runtime。
- `architecture-rules`：所有 bounded context 共用的 ArchUnit 規則（layer、Integration Event 邊界、循環、layer 歸屬），只供測試使用；規則對照表見該模組 `README.md`。
- `messaging`：Integration Event、Outbox／Inbox、Kafka 與 Spring Boot messaging adapters。

Gradle project path 分別為 `:archone-framework:foundation`、
`:archone-framework:domain-contract`、`:archone-framework:architecture-rules` 與 `:archone-framework:messaging:*`。
