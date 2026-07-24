package com.flowzati.archone.common.outbox;

public interface OutboxRepo {
  void append(Outbox outbox);
}
