package com.flowzati.archone.inventory.movement.application.result;

import com.flowzati.archone.inventory.movement.application.enums.StockOperationCancellationStatus;

/** Result of the local cancellation preparation transaction. */
public sealed interface StockOperationCancellationPreparation {

    record Terminal(StockOperationCancellationStatus status) implements StockOperationCancellationPreparation {

        public Terminal {
            if (status == null) {
                throw new IllegalArgumentException("Cancellation status is required");
            }
        }
    }

    record Continue(StockOperationCancellationCheckpoint checkpoint) implements StockOperationCancellationPreparation {

        public Continue {
            if (checkpoint == null) {
                throw new IllegalArgumentException("Cancellation checkpoint is required");
            }
        }
    }
}
