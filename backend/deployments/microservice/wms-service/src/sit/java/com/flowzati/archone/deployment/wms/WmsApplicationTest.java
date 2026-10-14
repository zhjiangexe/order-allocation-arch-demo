package com.flowzati.archone.deployment.wms;

import com.flowzati.archone.deployment.testsupport.IsolatedServiceStartup;

class WmsApplicationTest extends IsolatedServiceStartup {
    @Override
    protected Class<?> application() {
        return WmsApplication.class;
    }

    @Override
    protected String businessPackage() {
        return "com.flowzati.archone.wms";
    }
}
