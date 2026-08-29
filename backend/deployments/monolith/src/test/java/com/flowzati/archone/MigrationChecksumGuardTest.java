package com.flowzati.archone;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MigrationChecksumGuardTest {

    private static final Map<String, String> PUBLISHED_MIGRATION_SHA_256 = Map.ofEntries(
            Map.entry("V1__baseline.sql", "60b00a8642022e78eebff473cd24482fba68d8f04fea6fb342cea18a6698c94d"),
            Map.entry(
                    "V2__create_ordering_tables.sql",
                    "7da45a79808dbf421b233143c454316e57b6c071612a610ed2dbf7fb685d1015"),
            Map.entry("V3__create_stock_pools.sql", "0c066f1136679919025fde9db7ae0f13ee2049b20d2ab759c3ccc9baf53de40c"),
            Map.entry(
                    "V4__create_stock_movements.sql",
                    "21cf1467927d36c44f338b2ff2dc19940c2125af9e0f62f4dfaab4d637610820"),
            Map.entry(
                    "V5__create_event_inbox_and_outbox.sql",
                    "661f6a1c0854730ee84c00af55de72118f9237837138e172dfbb94c25a865f02"),
            Map.entry(
                    "V6__create_demand_lines_view.sql",
                    "021c8c98b9f8c1b274984f8e332e3e4e87ad3a5f11ab1abe7caa21ab498021ae"),
            Map.entry(
                    "V7__make_event_inbox_subscriber_aware.sql",
                    "97ea1c6b0a15ae89ba0e471b97cf413af58a65611359fdd1f2e307d9cafb7b73"),
            Map.entry(
                    "V8__add_generic_headers_to_event_outbox.sql",
                    "5c59d045a6b22ef2d55ce9696bc70b9b55ba1fd1c4bcf0152655725e70f06e8a"),
            Map.entry(
                    "V9__create_stock_receipt_requests.sql",
                    "f1bfe094c3e8af02e704af9daf32eaf618a5b046d9f63caa9bdfd78a5b7969ef"),
            Map.entry(
                    "V10__add_order_fulfillment_timestamp.sql",
                    "fec3938d99817181248dfe3fef341acdfa2159da434e594ed7bd77732dae7553"),
            Map.entry(
                    "V11__replace_backordered_status_with_supply_wait.sql",
                    "2ebe3ca251d14cf8a403a71b9c05f0208fbb32eb9cbd96ad1b658d4263b8dfdc"),
            Map.entry(
                    "V12__create_allocation_demands.sql",
                    "f486c2d7cae51039966e427c95e40b66762548ffa73a1fda809771cc6f4dfa16"),
            Map.entry(
                    "V13__backfill_allocation_demands.sql",
                    "20ad0246e7aa7d78582bff30e398fbff7c0e4eb6b9f21f3881eb177bee9aa7e2"),
            Map.entry(
                    "V14__validate_allocation_movement_links.sql",
                    "a22940c8ba5a089f879d514a316d9a6005e83775c4dd447383fbcacbd04a2740"),
            Map.entry(
                    "V15__enforce_one_move_per_allocation_demand_line.sql",
                    "9654d7d997ee960ffac5b95fe0bded012507f676341eac4725cc5b91ad4bd605"),
            Map.entry("V16__create_wms_tables.sql", "64e20412b6b23c29f42a3eeadb3ed02d455881eaa200bf5013339eed3e6cd297"),
            Map.entry(
                    "V17__index_due_wms_shipments.sql",
                    "8b31bdfbb4cdf4f3e256966e778591bfee092110b8bc281fb45df0d8cc35110a"),
            Map.entry(
                    "V18__correlate_order_cancellation_and_fulfillment.sql",
                    "7c25da80d2d40e26b0380cd0132cc417d8b58a41593fb65e287d7ed5687bb25c"),
            Map.entry(
                    "V19__persist_wms_inbound_and_waves.sql",
                    "f9fbe55a9fef01678c18fa262e5ca48a02a9c1013b01097d7fbdf45ab0552850"),
            Map.entry(
                    "V20__model_wms_shipment_cancellation_state.sql",
                    "2e352ab481652b80806252e4696aedc274cf9d26674ea1c3fc3a7664742a9fa0"));

    @Test
    @DisplayName("已發布的 V1-V20 migration checksum 不得被改寫")
    void publishedMigrationsRemainByteForByteUnchanged() throws IOException, NoSuchAlgorithmException {
        for (Map.Entry<String, String> migration : PUBLISHED_MIGRATION_SHA_256.entrySet()) {
            String resource = "/db/migration/" + migration.getKey();
            try (InputStream input = MigrationChecksumGuardTest.class.getResourceAsStream(resource)) {
                assertThat(input).as("published migration %s exists", resource).isNotNull();
                String actual = HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
                assertThat(actual).as("published migration %s", resource).isEqualTo(migration.getValue());
            }
        }
    }
}
