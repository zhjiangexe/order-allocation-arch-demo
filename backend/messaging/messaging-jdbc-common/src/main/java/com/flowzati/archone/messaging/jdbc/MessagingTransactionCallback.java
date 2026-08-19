package com.flowzati.archone.messaging.jdbc;

/** Callback executed inside a messaging transaction boundary. */
@FunctionalInterface
public interface MessagingTransactionCallback<T> {

    T execute();
}
