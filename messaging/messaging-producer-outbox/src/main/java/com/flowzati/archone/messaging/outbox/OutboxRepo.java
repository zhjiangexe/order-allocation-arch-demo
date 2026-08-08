package com.flowzati.archone.messaging.outbox;

public interface OutboxRepo {

  void append(Outbox outbox);
}
