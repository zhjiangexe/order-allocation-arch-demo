# Legacy Inbox migration bridge

`InboxRepo` and its Spring Data JPA implementation remain only so existing application use cases
can keep their current signatures during Gate D. The default JDBC consumer auto-configuration uses
`DuplicateMessageDetectorInboxRepo`, which delegates to the new atomic JDBC detector and still
requires the caller-owned transaction.

Gate E moves idempotency to the inbound handler decorator. Gate I removes `InboxRepo`,
`InboxRepoImpl`, `JpaEventInboxRepository`, and the JPA Inbox entities after the application no
longer imports them.
