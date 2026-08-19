package com.flowzati.archone.bootstrap.fulfillment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 在啟動期驗證 driver mode，避免拼錯設定後所有履約 consumer 都安靜停用。 */
@Configuration(proxyBeanMethods = false)
public class FulfillmentOrchestrationConfiguration {

    @Bean
    FulfillmentOrchestrationMode fulfillmentOrchestrationMode(
            @Value("${archone.fulfillment.orchestration-mode:events}") String configuredValue) {
        return FulfillmentOrchestrationMode.parse(configuredValue);
    }
}
