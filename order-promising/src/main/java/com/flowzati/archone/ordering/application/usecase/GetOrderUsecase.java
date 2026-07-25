package com.flowzati.archone.ordering.application.usecase;

import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class GetOrderUsecase {

  private final OrderRepository orderRepository;

  public GetOrderUsecase(OrderRepository orderRepository) {
    this.orderRepository = orderRepository;
  }

  public Order getOrder(UUID orderId) {
    return orderRepository.findById(orderId)
        .orElseThrow(() -> new NoSuchElementException("Order not found: " + orderId));
  }
}
