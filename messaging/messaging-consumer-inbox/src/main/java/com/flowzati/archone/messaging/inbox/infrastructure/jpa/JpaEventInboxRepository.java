package com.flowzati.archone.messaging.inbox.infrastructure.jpa;

import com.flowzati.archone.messaging.inbox.infrastructure.jpa.entity.InboxEntity;
import com.flowzati.archone.messaging.inbox.infrastructure.jpa.entity.InboxId;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaEventInboxRepository extends JpaRepository<InboxEntity, InboxId> {

  @Modifying
  @Query(
      value = """
          INSERT INTO event_inbox (subscriber_id, event_id, event_type, processed_at)
          SELECT :subscriberId, :eventId, :eventType, CURRENT_TIMESTAMP
           WHERE NOT EXISTS (
                 SELECT 1
                   FROM event_inbox
                  WHERE subscriber_id = 'legacy-global'
                    AND event_id = :eventId
                 )
          ON CONFLICT (subscriber_id, event_id) DO NOTHING
          """,
      nativeQuery = true
  )
  int claimIfNew(
      @Param("subscriberId") String subscriberId,
      @Param("eventId") UUID eventId,
      @Param("eventType") String eventType
  );
}
