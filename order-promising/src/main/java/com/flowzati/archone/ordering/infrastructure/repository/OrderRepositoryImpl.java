package com.flowzati.archone.ordering.infrastructure.repository;

import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.ordering.infrastructure.mapper.OrderMapper;
import com.flowzati.archone.ordering.infrastructure.repository.jpa.JpaOrderRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepositoryImpl implements OrderRepository {

  private final JpaOrderRepository repository;

  public OrderRepositoryImpl(JpaOrderRepository repository) {
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
    return repository.findAllByOrderByReceivedAtDescIdDesc(Limit.of(limit))
        .stream()
        .map(OrderMapper::toDomain)
        .toList();
  }
}
