package com.flowzati.archone.common.inbox;

import com.flowzati.archone.common.inbox.infrastructure.entity.InboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface JpaEventInboxRepository extends JpaRepository<InboxEntity, UUID> {
  @Modifying
  @Query(
      value = """
          INSERT INTO event_inbox (event_id, event_type, processed_at)
          VALUES (:eventId, :eventType, CURRENT_TIMESTAMP)
          ON CONFLICT (event_id) DO NOTHING
          """,
      nativeQuery = true
  )
  int claimIfNew(@Param("eventId") UUID eventId, @Param("eventType") String eventType);
}
