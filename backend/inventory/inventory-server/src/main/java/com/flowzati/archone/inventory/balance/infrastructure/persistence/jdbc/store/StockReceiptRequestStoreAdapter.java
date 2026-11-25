package com.flowzati.archone.inventory.balance.infrastructure.persistence.jdbc.store;

import com.flowzati.archone.foundation.error.ApplicationConflictException;
import com.flowzati.archone.inventory.balance.application.enums.StockBalanceErrorCode;
import com.flowzati.archone.inventory.balance.application.invocation.ConfirmStockReceiptCommand;
import com.flowzati.archone.inventory.balance.application.store.StockReceiptRequestStore;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** PostgreSQL atomic request claim; it always joins the facade's existing transaction. */
@Repository
public class StockReceiptRequestStoreAdapter implements StockReceiptRequestStore {

    private static final String INSERT_IF_NEW = """
      INSERT INTO stock_receipt_requests (
          receipt_id, owner_id, facility_id, location_id, sku_code,
          in_date, expiry_date, quantity, processed_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
      ON CONFLICT (receipt_id) DO NOTHING
      """;

    private static final String FIND_BY_ID = """
      SELECT receipt_id, owner_id, facility_id, location_id, sku_code,
             in_date, expiry_date, quantity
        FROM stock_receipt_requests
       WHERE receipt_id = ?
      """;

    private final JdbcOperations jdbcOperations;

    public StockReceiptRequestStoreAdapter(JdbcOperations jdbcOperations) {
        this.jdbcOperations = jdbcOperations;
    }

    @Override
    public boolean claimIfNew(ConfirmStockReceiptCommand command) {
        Objects.requireNonNull(command, "Stock receipt command is required");
        requireCallerTransaction();
        int inserted = jdbcOperations.update(
                INSERT_IF_NEW,
                command.receiptId(),
                command.ownerId(),
                command.facilityId(),
                command.locationId(),
                command.sku(),
                command.inDate(),
                command.expiryDate(),
                command.quantity());
        if (inserted == 1) {
            return true;
        }

        ConfirmStockReceiptCommand existing = jdbcOperations.queryForObject(
                FIND_BY_ID,
                (resultSet, rowNumber) -> new ConfirmStockReceiptCommand(
                        resultSet.getObject("receipt_id", UUID.class),
                        resultSet.getObject("owner_id", UUID.class),
                        resultSet.getObject("facility_id", UUID.class),
                        resultSet.getObject("location_id", UUID.class),
                        resultSet.getString("sku_code"),
                        resultSet.getObject("in_date", java.time.LocalDate.class),
                        resultSet.getObject("expiry_date", java.time.LocalDate.class),
                        resultSet.getInt("quantity")),
                command.receiptId());
        if (!command.equals(existing)) {
            throw new ApplicationConflictException(
                    StockBalanceErrorCode.STOCK_RECEIPT_REQUEST_CONFLICT,
                    "Receipt ID is already bound to a different request: " + command.receiptId());
        }
        return false;
    }

    private void requireCallerTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Stock receipt request claim requires an active caller transaction");
        }
    }
}
