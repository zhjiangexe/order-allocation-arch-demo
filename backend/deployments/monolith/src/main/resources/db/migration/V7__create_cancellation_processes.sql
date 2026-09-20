CREATE TABLE cancellation_processes (
    request_id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    reason VARCHAR(512) NOT NULL,
    state VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_cancellation_process_state CHECK (
        state IN ('WAITING_WMS', 'WAITING_ORDERING', 'COMPLETED', 'REJECTED', 'CONFLICT'))
);

CREATE UNIQUE INDEX ux_cancellation_process_active_order
    ON cancellation_processes (order_id)
    WHERE state IN ('WAITING_WMS', 'WAITING_ORDERING');
