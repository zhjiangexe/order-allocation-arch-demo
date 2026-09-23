package com.flowzati.archone.fulfillment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowzati.archone.fulfillment.application.invocation.FulfillmentCancellationCommand;
import com.flowzati.archone.fulfillment.application.port.CancellationRequestAcceptedPublisher;
import com.flowzati.archone.fulfillment.application.state.CancellationProcess;
import com.flowzati.archone.fulfillment.application.state.CancellationProcessState;
import com.flowzati.archone.fulfillment.application.state.FulfillmentCancellationStatus;
import com.flowzati.archone.fulfillment.application.store.CancellationProcessStore;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AcceptCancellationRequestUsecaseTest {
    private final UUID requestId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final Instant requestedAt = Instant.parse("2026-09-20T10:00:00Z");
    private final FulfillmentCancellationCommand command =
            new FulfillmentCancellationCommand(requestId, orderId, requestedAt, "customer request");
    private final CancellationProcessStore store = mock(CancellationProcessStore.class);
    private final CancellationRequestAcceptedPublisher publisher = mock(CancellationRequestAcceptedPublisher.class);
    private final AcceptCancellationRequestUsecase usecase = new AcceptCancellationRequestUsecase(publisher, store);

    @Test
    void newRequestPublishesOnce() {
        when(store.insert(any())).thenReturn(true);
        assertThat(usecase.accept(command).status()).isEqualTo(FulfillmentCancellationStatus.ACCEPTED);
        verify(publisher).publish(any());
    }

    @Test
    void replayDoesNotPublishAgain() {
        when(store.insert(any())).thenReturn(false);
        when(store.find(requestId))
                .thenReturn(Optional.of(new CancellationProcess(
                        requestId,
                        orderId,
                        requestedAt.plusNanos(100),
                        "customer request",
                        CancellationProcessState.WAITING_WMS)));
        assertThat(usecase.accept(command).status()).isEqualTo(FulfillmentCancellationStatus.ALREADY_REQUESTED);
        verifyNoInteractions(publisher);
    }

    @Test
    void differentPayloadConflicts() {
        when(store.insert(any())).thenReturn(false);
        when(store.find(requestId))
                .thenReturn(Optional.of(new CancellationProcess(
                        requestId, orderId, requestedAt, "another reason", CancellationProcessState.WAITING_WMS)));
        assertThat(usecase.accept(command).status()).isEqualTo(FulfillmentCancellationStatus.CONFLICT);
        verifyNoInteractions(publisher);
    }
}
