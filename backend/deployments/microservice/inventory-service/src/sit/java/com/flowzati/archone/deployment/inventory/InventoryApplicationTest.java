package com.flowzati.archone.deployment.inventory;

import com.flowzati.archone.deployment.testsupport.IsolatedServiceStartup;

class InventoryApplicationTest extends IsolatedServiceStartup {
    @Override
    protected Class<?> application() {
        return InventoryApplication.class;
    }

    @Override
    protected String businessPackage() {
        return "com.flowzati.archone.inventory";
    }
}
