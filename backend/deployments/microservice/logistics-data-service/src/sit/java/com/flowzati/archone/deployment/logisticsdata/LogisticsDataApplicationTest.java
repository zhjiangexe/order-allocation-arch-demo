package com.flowzati.archone.deployment.logisticsdata;

import com.flowzati.archone.deployment.testsupport.IsolatedServiceStartup;

class LogisticsDataApplicationTest extends IsolatedServiceStartup {
    @Override
    protected Class<?> application() {
        return LogisticsDataApplication.class;
    }

    @Override
    protected String businessPackage() {
        return "com.flowzati.archone.logisticsdata";
    }
}
