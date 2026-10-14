package com.flowzati.archone.deployment.ordering;

import com.flowzati.archone.deployment.testsupport.IsolatedServiceStartup;

class OrderingApplicationTest extends IsolatedServiceStartup {
    @Override
    protected Class<?> application() {
        return OrderingApplication.class;
    }

    @Override
    protected String businessPackage() {
        return "com.flowzati.archone.ordering";
    }
}
