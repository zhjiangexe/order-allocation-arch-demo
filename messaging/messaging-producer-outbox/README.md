# messaging-producer-outbox（legacy migration bridge）

Gate C 起，application runtime 的 `MessageProducer` 已改由
`messaging-producer-jdbc` + `messaging-spring-producer-jdbc` 寫入 Outbox。

本 module 暫時只保留 `OutboxRepo`、JPA entity/repository 與舊 producer，讓既有查詢、測試及
rolling deployment 不必在同一個 Gate 全部改寫。新的 production wiring 不得再依賴
`OutboxMessageProducer`。依 roadmap I8，本 migration bridge 會在 Gate I 移除。
