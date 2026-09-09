package com.flowzati.archone.ordering.application.usecase;

import com.flowzati.archone.ordering.application.store.OrderStore;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ListRecentOrdersUsecase {

    private final OrderStore orderStore;

    public ListRecentOrdersUsecase(OrderStore orderStore) {
        this.orderStore = orderStore;
    }

    public List<Order> listRecent(UUID ownerId, int limit) {
        return orderStore.findRecent(ownerId, limit);
    }

    /** 依下單時間遞減取最近 N 筆；`limit` 的有效範圍是 HTTP 契約，由邊界層決定。 */
    public List<Order> listRecent(int limit) {
        return orderStore.findRecent(limit);
    }
}
