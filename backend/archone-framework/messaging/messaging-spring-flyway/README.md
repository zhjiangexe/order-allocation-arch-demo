# messaging-spring-flyway

這是新 application 可選用的 messaging schema migration，不會由 starter 自動載入，也不會
使用 application 的 `classpath:db/migration` 或 schema history table。

使用者要明確 import `SpringMessagingFlywayConfiguration`，再自行決定啟動時機：

```java
Flyway messagingFlyway = messagingFlywayFactory.create(dataSource);
messagingFlyway.migrate();
```

預設使用 `archone_messaging` schema、獨立的
`flyway_archone_messaging_schema_history`，以及
`classpath:db/migration/archone-messaging`。既有 `order-promising` 不引用本 module；它繼續由
application-owned `V5`、`V7`、`V8` 管理 `public.event_inbox`／`public.event_outbox`。
