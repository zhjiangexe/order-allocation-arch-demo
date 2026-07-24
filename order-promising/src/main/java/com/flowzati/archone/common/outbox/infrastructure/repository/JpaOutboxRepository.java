package com.flowzati.archone.common.outbox.infrastructure.repository;

import com.flowzati.archone.common.outbox.infrastructure.entity.OutboxEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaOutboxRepository extends JpaRepository<OutboxEntity, UUID> {
}
