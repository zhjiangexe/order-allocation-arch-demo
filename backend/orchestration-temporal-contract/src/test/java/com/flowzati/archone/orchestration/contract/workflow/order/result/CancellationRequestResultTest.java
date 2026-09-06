package com.flowzati.archone.orchestration.contract.workflow.order.result;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.DefaultDataConverter;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CancellationRequestResultTest {

    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-7000-8000-000000000002");

    @ParameterizedTest
    @EnumSource(CancellationRequestStatus.class)
    void serializesTheStructuredResultWithoutDetail(CancellationRequestStatus status) {
        CancellationRequestResult result = new CancellationRequestResult(status, REQUEST_ID);

        Payload payload =
                DefaultDataConverter.STANDARD_INSTANCE.toPayload(result).orElseThrow();

        assertThat(payload.getData().toStringUtf8())
                .contains("\"status\":\"" + status.name() + "\"")
                .contains("\"effectiveRequestId\":\"" + REQUEST_ID + "\"")
                .doesNotContain("\"detail\"");
        assertThat(DefaultDataConverter.STANDARD_INSTANCE.fromPayload(
                        payload, CancellationRequestResult.class, CancellationRequestResult.class))
                .isEqualTo(result);
    }

    @ParameterizedTest
    @EnumSource(CancellationRequestStatus.class)
    void readsLegacyPayloadsContainingDetail(CancellationRequestStatus status) {
        String legacyJson = """
                {"status":"%s","effectiveRequestId":"%s","detail":"Legacy response detail"}
                """.formatted(status.name(), REQUEST_ID);
        Payload payload = Payload.newBuilder()
                .putMetadata("encoding", ByteString.copyFromUtf8("json/plain"))
                .setData(ByteString.copyFromUtf8(legacyJson))
                .build();

        CancellationRequestResult result = DefaultDataConverter.STANDARD_INSTANCE.fromPayload(
                payload, CancellationRequestResult.class, CancellationRequestResult.class);

        assertThat(result).isEqualTo(new CancellationRequestResult(status, REQUEST_ID));
    }
}
