package com.flowzati.archone.orderfulfillment.infrastructure.persistence;

import com.flowzati.archone.orderfulfillment.application.CancellationProcess;
import com.flowzati.archone.orderfulfillment.application.CancellationProcessState;
import com.flowzati.archone.orderfulfillment.application.invocation.OrderingCancellationOutcomeCommand;
import com.flowzati.archone.orderfulfillment.application.invocation.WmsCancellationOutcomeCommand;
import com.flowzati.archone.orderfulfillment.application.port.CancellationProcessStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCancellationProcessStore implements CancellationProcessStore {

    private static final String SELECT = "SELECT request_id, order_id, requested_at, reason, state, "
            + "wms_outcome, ordering_outcome "
            + "FROM cancellation_processes WHERE request_id = ?";

    private final JdbcTemplate jdbc;

    public JdbcCancellationProcessStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<CancellationProcess> find(UUID requestId) {
        return jdbc.query(SELECT, JdbcCancellationProcessStore::map, requestId).stream()
                .findFirst();
    }

    @Override
    public Optional<CancellationProcess> lock(UUID requestId) {
        return jdbc.query(SELECT + " FOR UPDATE", JdbcCancellationProcessStore::map, requestId).stream()
                .findFirst();
    }

    @Override
    public boolean insert(CancellationProcess process) {
        return jdbc.update(
                        "INSERT INTO cancellation_processes (request_id, order_id, requested_at, reason, state) "
                                + "VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                        process.requestId(),
                        process.orderId(),
                        Timestamp.from(process.requestedAt()),
                        process.reason(),
                        process.state().name())
                == 1;
    }

    @Override
    public void recordWmsOutcome(
            UUID requestId, CancellationProcessState next, WmsCancellationOutcomeCommand.Outcome outcome) {
        int changed = jdbc.update(
                "UPDATE cancellation_processes SET state = ?, wms_outcome = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE request_id = ? AND state = 'WAITING_WMS' AND wms_outcome IS NULL",
                next.name(),
                outcome.name(),
                requestId);
        if (changed != 1) {
            throw new IllegalStateException("Cancellation WMS outcome changed unexpectedly: " + requestId);
        }
    }

    @Override
    public void recordOrderingOutcome(
            UUID requestId, CancellationProcessState next, OrderingCancellationOutcomeCommand.Outcome outcome) {
        int changed = jdbc.update(
                "UPDATE cancellation_processes SET state = ?, ordering_outcome = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE request_id = ? AND state = 'WAITING_ORDERING' AND ordering_outcome IS NULL",
                next.name(),
                outcome.name(),
                requestId);
        if (changed != 1) {
            throw new IllegalStateException("Cancellation Ordering outcome changed unexpectedly: " + requestId);
        }
    }

    private static CancellationProcess map(ResultSet row, int ignored) throws SQLException {
        return new CancellationProcess(
                row.getObject("request_id", UUID.class),
                row.getObject("order_id", UUID.class),
                row.getTimestamp("requested_at").toInstant(),
                row.getString("reason"),
                CancellationProcessState.valueOf(row.getString("state")),
                row.getString("wms_outcome") == null
                        ? null
                        : WmsCancellationOutcomeCommand.Outcome.valueOf(row.getString("wms_outcome")),
                row.getString("ordering_outcome") == null
                        ? null
                        : OrderingCancellationOutcomeCommand.Outcome.valueOf(row.getString("ordering_outcome")));
    }
}
