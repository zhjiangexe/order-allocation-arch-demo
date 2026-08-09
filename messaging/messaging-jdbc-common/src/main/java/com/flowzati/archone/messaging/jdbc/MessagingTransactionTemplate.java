package com.flowzati.archone.messaging.jdbc;

/**
 * Framework-neutral transaction port.
 *
 * <p>Consumer adapters use {@link #execute(MessagingTransactionCallback)} to open or join a local
 * database transaction. Producer adapters use {@link #requireActive()} to enforce caller-owned
 * transaction semantics without importing Spring annotations.
 */
public interface MessagingTransactionTemplate {

  <T> T execute(MessagingTransactionCallback<T> callback);

  void requireActive();

  boolean isActive();
}
