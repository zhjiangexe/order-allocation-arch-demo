package com.flowzati.archone.wms;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.wms.inbound.application.command.ConfirmArrivalCommand;
import com.flowzati.archone.wms.inbound.application.command.ConfirmPutawayCommand;
import com.flowzati.archone.wms.inbound.application.command.RecordInspectionCommand;
import com.flowzati.archone.wms.inbound.application.command.RegisterInboundOperationCommand;
import com.flowzati.archone.wms.inbound.application.usecase.ConfirmArrivalUsecase;
import com.flowzati.archone.wms.inbound.application.usecase.ConfirmPutawayUsecase;
import com.flowzati.archone.wms.inbound.application.usecase.RecordInspectionUsecase;
import com.flowzati.archone.wms.inbound.application.usecase.RegisterInboundOperationUsecase;
import com.flowzati.archone.wms.inbound.domain.aggregate.InboundOperation;
import com.flowzati.archone.wms.inbound.domain.repository.InboundOperationRepository;
import com.flowzati.archone.wms.inbound.domain.type.InboundStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InboundProcessTest {

    private static final Instant T0 = Instant.parse("2026-08-06T02:00:00Z");

    private final InMemoryInboundRepository repository = new InMemoryInboundRepository();
    private RegisterInboundOperationUsecase register;
    private ConfirmArrivalUsecase confirmArrival;
    private RecordInspectionUsecase inspect;
    private ConfirmPutawayUsecase confirmPutaway;

    @BeforeEach
    void setUp() {
        register = new RegisterInboundOperationUsecase(repository);
        confirmArrival = new ConfirmArrivalUsecase(repository);
        inspect = new RecordInspectionUsecase(repository);
        confirmPutaway = new ConfirmPutawayUsecase(repository);
    }

    @Test
    void completesArrivalInspectionAndPutaway() {
        UUID operationId = UUID.randomUUID();
        InboundOperation operation = register.handle(new RegisterInboundOperationCommand(
                operationId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "ASN-1001",
                List.of(new RegisterInboundOperationCommand.ExpectedLine("SKU-A", 10)),
                T0));

        confirmArrival.handle(new ConfirmArrivalCommand(operationId, T0.plusSeconds(10)));
        inspect.handle(new RecordInspectionCommand(operationId, true, null, T0.plusSeconds(20)));
        confirmPutaway.handle(new ConfirmPutawayCommand(
                operationId,
                List.of(new ConfirmPutawayCommand.ActualLine(
                        "SKU-A", UUID.randomUUID(), LocalDate.of(2026, 8, 6), LocalDate.of(2027, 8, 6), 10)),
                T0.plusSeconds(30)));

        assertThat(operation.status()).isEqualTo(InboundStatus.COMPLETED);
    }

    @Test
    void quarantinesRejectedGoods() {
        UUID operationId = UUID.randomUUID();
        InboundOperation operation = register.handle(new RegisterInboundOperationCommand(
                operationId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "ASN-1002",
                List.of(new RegisterInboundOperationCommand.ExpectedLine("SKU-B", 4)),
                T0));
        confirmArrival.handle(new ConfirmArrivalCommand(operationId, T0.plusSeconds(10)));

        inspect.handle(new RecordInspectionCommand(operationId, false, "Damaged packaging", T0.plusSeconds(20)));

        assertThat(operation.status()).isEqualTo(InboundStatus.QUARANTINED);
    }

    @Test
    void treatsRepeatedCommandsAsIdempotentRedeliveries() {
        UUID operationId = UUID.randomUUID();
        register.handle(new RegisterInboundOperationCommand(
                operationId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "ASN-RETRY",
                List.of(new RegisterInboundOperationCommand.ExpectedLine("SKU-C", 3)),
                T0));

        ConfirmArrivalCommand arrival = new ConfirmArrivalCommand(operationId, T0.plusSeconds(10));
        confirmArrival.handle(arrival);
        confirmArrival.handle(arrival);
        assertThat(repository.findById(operationId))
                .hasValueSatisfying(operation -> assertThat(operation.status()).isEqualTo(InboundStatus.ARRIVED));

        RecordInspectionCommand inspection = new RecordInspectionCommand(operationId, true, null, T0.plusSeconds(20));
        inspect.handle(inspection);
        inspect.handle(inspection);
        assertThat(repository.findById(operationId))
                .hasValueSatisfying(
                        operation -> assertThat(operation.status()).isEqualTo(InboundStatus.READY_FOR_PUTAWAY));

        ConfirmPutawayCommand putaway = new ConfirmPutawayCommand(
                operationId,
                List.of(new ConfirmPutawayCommand.ActualLine(
                        "SKU-C", UUID.randomUUID(), LocalDate.of(2026, 8, 6), LocalDate.of(2027, 8, 6), 3)),
                T0.plusSeconds(30));
        confirmPutaway.handle(putaway);
        confirmPutaway.handle(putaway);
        assertThat(repository.findById(operationId))
                .hasValueSatisfying(operation -> assertThat(operation.status()).isEqualTo(InboundStatus.COMPLETED));
    }

    private static final class InMemoryInboundRepository implements InboundOperationRepository {

        private final Map<UUID, InboundOperation> operations = new LinkedHashMap<>();

        @Override
        public Optional<InboundOperation> findById(UUID inboundOperationId) {
            return Optional.ofNullable(operations.get(inboundOperationId));
        }

        @Override
        public Optional<InboundOperation> findByExternalReference(String externalReference) {
            return operations.values().stream()
                    .filter(operation -> operation.externalReference().equals(externalReference))
                    .findFirst();
        }

        @Override
        public void save(InboundOperation operation) {
            operations.put(operation.id(), operation);
        }
    }
}
