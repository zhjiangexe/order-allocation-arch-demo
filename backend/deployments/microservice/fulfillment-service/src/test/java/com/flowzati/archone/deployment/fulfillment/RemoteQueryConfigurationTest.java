package com.flowzati.archone.deployment.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.inventory.api.operation.InventoryOperationQueryApi;
import com.flowzati.archone.ordering.api.query.OrderQueryApi;
import com.flowzati.archone.wms.api.shipment.WmsShipmentQueryApi;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClientResponseException;

class RemoteQueryConfigurationTest {
    @Test
    void usesSharedHttpContractsAndPropagatesDownstreamFailures() throws Exception {
        UUID orderId = UUID.randomUUID();
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().toString();
            requests.add(path);
            String body = path.startsWith("/internal/ordering/orders/")
                    ? "{\"orderId\":\"" + orderId + "\",\"lines\":[],\"releasePriority\":0}"
                    : path.startsWith("/internal/inventory/") ? "{\"moves\":[]}" : "[]";
            int status = path.contains("unavailable") ? 503 : 200;
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            new ApplicationContextRunner()
                    .withUserConfiguration(RemoteQueryConfiguration.class)
                    .withPropertyValues(
                            "archone.services.ordering=" + url,
                            "archone.services.inventory=" + url,
                            "archone.services.wms=" + url)
                    .run(context -> {
                        assertThat(context.getBean(OrderQueryApi.class)
                                        .get(orderId)
                                        .orderId())
                                .isEqualTo(orderId);
                        assertThat(context.getBean(InventoryOperationQueryApi.class)
                                        .findPrimaryOrder(orderId)
                                        .moves())
                                .isEmpty();
                        assertThat(context.getBean(WmsShipmentQueryApi.class).findByOrderId(orderId))
                                .isEmpty();
                        assertThat(requests)
                                .containsExactly(
                                        "/internal/ordering/orders/" + orderId,
                                        "/internal/inventory/stock-operations/primary-order?orderId=" + orderId,
                                        "/internal/wms/shipments?orderId=" + orderId);
                    });
            new ApplicationContextRunner()
                    .withUserConfiguration(RemoteQueryConfiguration.class)
                    .withPropertyValues("archone.services.ordering=" + url + "/unavailable")
                    .run(context -> assertThatThrownBy(
                                    () -> context.getBean(OrderQueryApi.class).get(orderId))
                            .isInstanceOf(RestClientResponseException.class));
        } finally {
            server.stop(0);
        }
    }
}
