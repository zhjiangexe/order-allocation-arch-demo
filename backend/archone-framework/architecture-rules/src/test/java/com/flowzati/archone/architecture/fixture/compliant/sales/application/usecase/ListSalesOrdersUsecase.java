package com.flowzati.archone.architecture.fixture.compliant.sales.application.usecase;

import com.flowzati.archone.architecture.fixture.compliant.sales.application.store.SalesOrderViewStore;
import java.util.List;

/** 查詢型 Usecase：名稱以 List 開頭，可以不收 Command，只呼叫 store 的讀取 method。 */
public class ListSalesOrdersUsecase {
    private final SalesOrderViewStore salesOrderViewStore;

    public ListSalesOrdersUsecase(SalesOrderViewStore salesOrderViewStore) {
        this.salesOrderViewStore = salesOrderViewStore;
    }

    public List<String> list() {
        return salesOrderViewStore.findOrderIds();
    }
}
