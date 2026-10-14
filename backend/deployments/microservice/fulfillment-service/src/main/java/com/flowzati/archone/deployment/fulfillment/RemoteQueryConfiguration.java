package com.flowzati.archone.deployment.fulfillment;

import com.flowzati.archone.fulfillment.configuration.OrderFulfillmentProperties;
import com.flowzati.archone.inventory.api.operation.InventoryOperationQueryApi;
import com.flowzati.archone.ordering.api.query.OrderQueryApi;
import com.flowzati.archone.wms.api.shipment.WmsShipmentQueryApi;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/** Read-only remote calls use bounded timeouts; business commands keep their event boundaries. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrderFulfillmentProperties.class)
public class RemoteQueryConfiguration {
    @Bean
    OrderQueryApi remoteOrderQuery(@Value("${archone.services.ordering:http://localhost:28880}") String url) {
        return client(url, OrderQueryApi.class);
    }

    @Bean
    InventoryOperationQueryApi remoteInventoryQuery(
            @Value("${archone.services.inventory:http://localhost:28881}") String url) {
        return client(url, InventoryOperationQueryApi.class);
    }

    @Bean
    WmsShipmentQueryApi remoteShipmentQuery(@Value("${archone.services.wms:http://localhost:28882}") String url) {
        return client(url, WmsShipmentQueryApi.class);
    }

    private static <T> T client(String url, Class<T> api) {
        var httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var requests = new JdkClientHttpRequestFactory(httpClient);
        requests.setReadTimeout(Duration.ofSeconds(5));
        var restClient =
                RestClient.builder().baseUrl(url).requestFactory(requests).build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(api);
    }
}
