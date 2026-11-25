package com.flowzati.archone.architecture.fixture.compliant.sales.infrastructure.persistence.jdbc.store;

import com.flowzati.archone.architecture.fixture.compliant.sales.application.store.SalesOrderViewStore;
import java.util.List;

public class SalesOrderViewStoreAdapter implements SalesOrderViewStore {

    @Override
    public List<String> findOrderIds() {
        return new SalesOrderViewRows().ids();
    }
}
