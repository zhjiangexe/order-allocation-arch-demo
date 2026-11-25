package com.flowzati.archone.wms.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.flowzati.archone.ArchoneApplication;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import com.flowzati.archone.wms.picking.application.store.PickingWorkStore;
import com.flowzati.archone.wms.picking.domain.aggregate.PickingWork;
import com.flowzati.archone.wms.process.application.invocation.ReleaseShipmentsToPickingCommand;
import com.flowzati.archone.wms.process.application.usecase.ReleaseShipmentsToPickingUsecase;
import com.flowzati.archone.wms.shipment.application.invocation.CreateShipmentCommand;
import com.flowzati.archone.wms.shipment.application.invocation.MovementLine;
import com.flowzati.archone.wms.shipment.application.store.ShipmentStore;
import com.flowzati.archone.wms.shipment.application.usecase.CreateShipmentUsecase;
import com.flowzati.archone.wms.shipment.domain.enums.ShipmentStatus;
import com.flowzati.archone.wms.wave.application.invocation.PlanWaveCommand;
import com.flowzati.archone.wms.wave.application.invocation.ReleaseWaveCommand;
import com.flowzati.archone.wms.wave.application.usecase.PlanWaveUsecase;
import com.flowzati.archone.wms.wave.application.usecase.ReleaseWaveUsecase;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * WMS 內部事件的 Listener 與 Usecase 必須在發布事件的那個交易內執行：任何一方失敗，兩邊一起 rollback，
 * 不會留下「波次已釋放、揀貨工作卻沒建出來」的半套狀態。
 */
@SpringBootTest(
        classes = ArchoneApplication.class,
        properties = "spring.kafka.listener.auto-startup=false",
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(PostgreSQLTestConfiguration.class)
class WarehouseProcessEventTransactionIntegrationTest {

    private static final Instant NOW = PostgreSQLTestConfiguration.NOW;

    @Autowired
    private CreateShipmentUsecase createShipmentUsecase;

    @Autowired
    private PlanWaveUsecase planWaveUsecase;

    @Autowired
    private ReleaseWaveUsecase releaseWaveUsecase;

    @Autowired
    private ReleaseShipmentsToPickingUsecase releaseShipmentsToPickingUsecase;

    @Autowired
    private ShipmentStore shipmentStore;

    @MockitoSpyBean
    private PickingWorkStore pickingWorkStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearDatabase() {
        jdbcTemplate.update("DELETE FROM event_outbox");
        jdbcTemplate.update("DELETE FROM wms_dispatches");
        jdbcTemplate.update("DELETE FROM wms_pick_tasks");
        jdbcTemplate.update("DELETE FROM wms_picking_works");
        jdbcTemplate.update("DELETE FROM wms_shipment_lines");
        jdbcTemplate.update("DELETE FROM wms_wave_assignments");
        jdbcTemplate.update("DELETE FROM wms_waves");
        jdbcTemplate.update("DELETE FROM wms_shipments");
    }

    @Test
    @DisplayName("波次釋放後，同一交易內建立揀貨工作並更新出貨單")
    void releasingAWaveCreatesPickingWorkInTheSameTransaction() {
        UUID shipmentId = createShipment();
        UUID waveId = planWave(shipmentId);

        releaseWaveUsecase.handle(new ReleaseWaveCommand(waveId, NOW));

        assertThat(waveStatus(waveId)).isEqualTo("RELEASED");
        assertThat(pickingWorkStore.findByShipmentId(shipmentId))
                .map(PickingWork::waveId)
                .contains(waveId);
        assertThat(shipmentStore.findById(shipmentId).orElseThrow().status()).isEqualTo(ShipmentStatus.RELEASED);
    }

    @Test
    @DisplayName("Listener 的 Usecase 失敗時，波次釋放本身也一起 rollback")
    void listenerFailureRollsBackTheWaveRelease() {
        UUID shipmentId = createShipment();
        UUID waveId = planWave(shipmentId);
        ShipmentStatus statusBeforeRelease =
                shipmentStore.findById(shipmentId).orElseThrow().status();
        doThrow(new IllegalStateException("picking work write failed"))
                .when(pickingWorkStore)
                .save(any());

        assertThatThrownBy(() -> releaseWaveUsecase.handle(new ReleaseWaveCommand(waveId, NOW)))
                .hasRootCauseMessage("picking work write failed");

        assertThat(waveStatus(waveId)).isEqualTo("PLANNED");
        assertThat(shipmentStore.findById(shipmentId).orElseThrow().status()).isEqualTo(statusBeforeRelease);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM wms_picking_works WHERE shipment_id = ?", Integer.class, shipmentId))
                .isZero();
    }

    @Test
    @DisplayName("Listener 的 Usecase 不能脫離發布者的交易單獨執行")
    void processUsecasesRefuseToRunOutsideThePublishersTransaction() {
        UUID shipmentId = createShipment();

        assertThatThrownBy(() -> releaseShipmentsToPickingUsecase.execute(
                        new ReleaseShipmentsToPickingCommand(UUID.randomUUID(), List.of(shipmentId), NOW)))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(pickingWorkStore.findByShipmentId(shipmentId)).isEmpty();
    }

    private UUID createShipment() {
        UUID shipmentId = UUID.randomUUID();
        createShipmentUsecase.handle(new CreateShipmentCommand(
                shipmentId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(new MovementLine(UUID.randomUUID(), UUID.randomUUID(), "SKU-1", UUID.randomUUID(), 3)),
                NOW.plusSeconds(3600),
                50,
                NOW));
        return shipmentId;
    }

    private UUID planWave(UUID shipmentId) {
        UUID waveId = UUID.randomUUID();
        var shipment = shipmentStore.findById(shipmentId).orElseThrow();
        planWaveUsecase.handle(new PlanWaveCommand(
                waveId, shipment.facilityId(), "SIT", shipment.dispatchBy(), 1, 1, 1, 3, NOW, List.of(shipmentId)));
        return waveId;
    }

    private String waveStatus(UUID waveId) {
        return jdbcTemplate.queryForObject("SELECT status FROM wms_waves WHERE id = ?", String.class, waveId);
    }
}
