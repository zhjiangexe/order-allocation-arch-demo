# Archone Framework

此目錄集中不屬於任何 bounded context 的共用技術模組：

- `foundation`：共用錯誤、識別碼、時間與模擬工具。
- `domain-contract`：framework-neutral Design by Contract runtime。
- `messaging`：Integration Event、Outbox／Inbox、Kafka 與 Spring Boot messaging adapters。

Gradle project path 分別為 `:archone-framework:foundation`、
`:archone-framework:domain-contract` 與 `:archone-framework:messaging:*`。
