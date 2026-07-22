package com.flowzati.archone.common.outbox;

public interface OutboxStore {
  void save(Outbox outbox);
}
