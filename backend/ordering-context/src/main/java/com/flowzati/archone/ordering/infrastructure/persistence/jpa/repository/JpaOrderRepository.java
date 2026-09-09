package com.flowzati.archone.ordering.infrastructure.persistence.jpa.repository;

import com.flowzati.archone.ordering.infrastructure.persistence.jpa.model.OrderEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaOrderRepository extends JpaRepository<OrderEntity, UUID> {

    List<OrderEntity> findByOwnerIdOrderByReceivedAtDescIdDesc(UUID ownerId, Limit limit);

    // Order status is a projection only. Allocation waiting queues are owned by stock movements.
    List<OrderEntity> findAllByOrderByReceivedAtDescIdDesc(Limit limit);
}
