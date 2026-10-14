package com.flowzati.archone.deployment.fulfillment;

import com.flowzati.archone.deployment.testsupport.IsolatedServiceStartup;

class FulfillmentApplicationTest extends IsolatedServiceStartup {
    @Override
    protected Class<?> application() {
        return FulfillmentApplication.class;
    }

    @Override
    protected String businessPackage() {
        return "com.flowzati.archone.fulfillment";
    }
}
