# AGENTS.md instructions

## Language

- Unless the user explicitly requests another language, respond in Traditional Chinese (zh-TW).
- Keep code, command names, file paths, API names, and error messages in their original language.

## Java formatting

- Java formatting is defined by Palantir Java Format with a 120-column width.
- After modifying Java files, run `cd backend && ./gradlew spotlessApply`.
- Before committing Java changes, run `cd backend && ./gradlew spotlessCheck`.

## Integration Event boundaries

- Application／Domain business code不得直接依賴 versioned Integration Event contract、
  `IntegrationEventPublication` 或 `IntegrationEventPublisher`。
- 由 business state transition 產生的 outbound event 使用：
  `Application Event → application.port.*Publisher → infrastructure.messaging.*IntegrationEventAdapter`。
- Application Event 使用已發生事實的過去式名稱；Publisher port 一次接收一個完整 event。
- 同一個完整 Application Event 可以由一個 Adapter 原子轉成多個、面向不同 audience 的 Integration Event；
  不要只為了不同外部表示，讓 Usecase 重複發布多個 Application Event。所有 publication 仍須在原 business
  transaction 內同步交給 `IntegrationEventPublisher`。
- 跨 bounded context 的複雜語意轉換可抽成 `infrastructure.messaging.*Translator`；Translator 必須是純轉換，
  不查資料庫、不修改 Domain、不直接發布。簡單一對一 mapping 留在 Adapter。
- 收到 Integration Event 後必須產生另一個 Integration Event 時，Consumer 只把 versioned contract 與必要的
  correlation identity 轉成 normalized command；Usecase 在同一 transaction 內透過 Application Event 與
  Publisher port 發布結果。不得讓 Consumer 自行編排 Usecase Result 到 outbound publication。
- `IntegrationEventPublisher` 只由 `*IntegrationEventAdapter` 或 shared messaging infrastructure 使用；bounded
  context 不得直接呼叫 `MessageProducer`。
- Adapter 名稱描述直接銜接的 Integration Event boundary，不使用 Outbox、Kafka、JDBC 等可替換的下層傳輸
  機制命名。
