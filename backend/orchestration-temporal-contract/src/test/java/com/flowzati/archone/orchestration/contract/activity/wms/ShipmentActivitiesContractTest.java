package com.flowzati.archone.orchestration.contract.activity.wms;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.AssignedStockMove;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.StockOperationAssignedInput;
import com.google.protobuf.ByteString;
import io.temporal.activity.ActivityMethod;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.DefaultDataConverter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShipmentActivitiesContractTest {

    private static final UUID ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final String LEGACY_INPUT_JSON = """
            {
              "processId": "process-1",
              "assignment": {
                "stockOperationId": "%1$s",
                "orderId": "%1$s",
                "ownerId": "%1$s",
                "facilityId": "%1$s",
                "moves": [{
                  "orderLineId": "%1$s",
                  "moveId": "%1$s",
                  "skuCode": "SKU-1",
                  "sourceLocationId": "%1$s",
                  "quantity": 3
                }],
                "dispatchBy": "2026-08-24T12:00:00Z",
                "releasePriority": 80,
                "assignedAt": "2026-08-24T10:00:00Z"
              }
            }
            """.formatted(ID);

    @Test
    void keepsTheExistingActivityTypeAfterRenamingTheJavaMethod() throws Exception {
        var method = ShipmentActivities.class.getMethod("releaseToWarehouse", ReleaseToWarehouseActivityInput.class);

        assertThat(method.getAnnotation(ActivityMethod.class).name()).isEqualTo("CreateWmsShipment");
        assertThat(method.getReturnType()).isEqualTo(ReleaseToWarehouseActivityResult.class);
    }

    @Test
    void readsAndWritesTheExistingShipmentCreationInputPayload() throws Exception {
        ReleaseToWarehouseActivityInput input = DefaultDataConverter.STANDARD_INSTANCE.fromPayload(
                jsonPayload(LEGACY_INPUT_JSON),
                ReleaseToWarehouseActivityInput.class,
                ReleaseToWarehouseActivityInput.class);

        assertThat(input)
                .isEqualTo(new ReleaseToWarehouseActivityInput(
                        "process-1",
                        new StockOperationAssignedInput(
                                ID,
                                ID,
                                ID,
                                ID,
                                List.of(new AssignedStockMove(ID, ID, "SKU-1", ID, 3)),
                                Instant.parse("2026-08-24T12:00:00Z"),
                                80,
                                Instant.parse("2026-08-24T10:00:00Z"))));
        assertSerializedJson(input, LEGACY_INPUT_JSON);
    }

    @Test
    void readsAndWritesTheExistingShipmentCreationResultPayload() throws Exception {
        String legacyJson = "{\"shipmentId\":\"" + ID + "\"}";
        ReleaseToWarehouseActivityResult result = DefaultDataConverter.STANDARD_INSTANCE.fromPayload(
                jsonPayload(legacyJson),
                ReleaseToWarehouseActivityResult.class,
                ReleaseToWarehouseActivityResult.class);

        assertThat(result).isEqualTo(new ReleaseToWarehouseActivityResult(ID));
        assertSerializedJson(result, legacyJson);
    }

    private static Payload jsonPayload(String json) {
        return Payload.newBuilder()
                .putMetadata("encoding", ByteString.copyFromUtf8("json/plain"))
                .setData(ByteString.copyFromUtf8(json))
                .build();
    }

    private static void assertSerializedJson(Object value, String expectedJson) throws Exception {
        Payload payload =
                DefaultDataConverter.STANDARD_INSTANCE.toPayload(value).orElseThrow();
        ObjectMapper mapper = new ObjectMapper();

        assertThat(payload.getMetadataMap()).containsEntry("encoding", ByteString.copyFromUtf8("json/plain"));
        assertThat(mapper.readTree(payload.getData().toStringUtf8())).isEqualTo(mapper.readTree(expectedJson));
    }
}
