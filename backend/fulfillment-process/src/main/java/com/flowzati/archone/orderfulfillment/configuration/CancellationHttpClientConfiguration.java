package com.flowzati.archone.orderfulfillment.configuration;

import com.flowzati.archone.ordering.api.cancellation.OrderCancellationApi;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/** Remote deployment uses HTTP proxies; the monolith supplies local server implementations instead. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "archone.fulfillment.rpc.transport", havingValue = "http")
public class CancellationHttpClientConfiguration {

    @Bean
    OrderCancellationApi orderCancellationApi(@Value("${archone.fulfillment.rpc.ordering-base-url}") String baseUrl) {
        HttpClient httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(OrderCancellationApi.class);
    }
}
