package com.flowzati.archone.inventory.movement.application.result;

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
