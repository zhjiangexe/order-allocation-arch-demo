package com.flowzati.archone.common.inbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface InboxStore extends JpaRepository {
  @Modifying
  @Query(
      value = "INSERT INTO event_inbox (event_id) VALUES (:eventId) ON CONFLICT (event_id) DO NOTHING",
      nativeQuery = true   // ← 一定要開這個，JPQL 辦不到
  )
  int claimIfNew(@Param("eventId")UUID eventId);
}
