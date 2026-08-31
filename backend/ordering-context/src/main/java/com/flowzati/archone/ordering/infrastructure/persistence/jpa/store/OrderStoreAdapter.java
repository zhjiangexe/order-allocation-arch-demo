package com.flowzati.archone.ordering.infrastructure.persistence.jpa.store;

import com.flowzati.archone.ordering.application.store.OrderStore;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import com.flowzati.archone.ordering.infrastructure.persistence.jpa.mapper.OrderMapper;
import com.flowzati.archone.ordering.infrastructure.persistence.jpa.repository.JpaOrderRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;

@Repository
public class OrderStoreAdapter implements OrderStore {

    private final JpaOrderRepository repository;

    public OrderStoreAdapter(JpaOrderRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(Order order) {
        repository.save(OrderMapper.toEntity(order));
    }

    @Override
    public Optional<Order> findById(UUID orderId) {
        return repository.findById(orderId).map(OrderMapper::toDomain);
    }

    @Override
    public List<Order> findRecent(int limit) {
        return repository.findAllByOrderByReceivedAtDescIdDesc(Limit.of(limit)).stream()
                .map(OrderMapper::toDomain)
                .toList();
    }
}
