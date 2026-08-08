package com.flowzati.archone.messaging.outbox.infrastructure.jpa;

import com.flowzati.archone.messaging.outbox.infrastructure.jpa.entity.OutboxEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaOutboxRepository extends JpaRepository<OutboxEntity, UUID> {
}
