package com.flowzati.archone.ordering.api.cancellation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@SpringBootTest(classes = OrderCancellationHttpServiceClientTest.TestApplication.class)
class OrderCancellationHttpServiceClientTest {

    private static final HttpServer SERVER = startServer();

    @Autowired
    private OrderCancellationApi api;

    @DynamicPropertySource
    static void clientUrl(DynamicPropertyRegistry registry) {
        registry.add(
                "ordering.rpc.url",
                () -> "http://localhost:" + SERVER.getAddress().getPort());
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Test
    void httpServiceProxyCallsTheSharedHttpContract() {
        OrderCancellationAssessment result = api.assess(new OrderCancellationRequest(
                UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-08-20T08:00:00Z"), "Customer changed mind"));

        assertThat(result).isEqualTo(OrderCancellationAssessment.CANCELLABLE);
    }

    @Test
    void serverErrorIsNotInterpretedAsAssessment() {
        assertThatThrownBy(() -> api.assess(new OrderCancellationRequest(
                        UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-08-20T08:00:00Z"), "trigger-error")))
                .isInstanceOf(HttpServerErrorException.class);
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/internal/ordering/cancellation/assess", exchange -> {
                String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (requestBody.contains("trigger-error")) {
                    exchange.sendResponseHeaders(503, -1);
                    exchange.close();
                    return;
                }
                byte[] body = "\"CANCELLABLE\"".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot start RPC test server", exception);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
        @Bean
        OrderCancellationApi orderCancellationApi(Environment environment) {
            RestClient client = RestClient.builder()
                    .baseUrl(environment.getRequiredProperty("ordering.rpc.url"))
                    .build();
            return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client))
                    .build()
                    .createClient(OrderCancellationApi.class);
        }
    }
}
