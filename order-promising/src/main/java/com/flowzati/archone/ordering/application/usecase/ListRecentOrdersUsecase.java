package com.flowzati.archone.ordering.application.usecase;

import com.flowzati.archone.ordering.domain.aggregate.Order;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ListRecentOrdersUsecase {

    private final OrderRepository orderRepository;

    public ListRecentOrdersUsecase(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /** 依下單時間遞減取最近 N 筆；`limit` 的有效範圍是 HTTP 契約，由邊界層決定。 */
    public List<Order> listRecent(int limit) {
        return orderRepository.findRecent(limit);
    }
}
