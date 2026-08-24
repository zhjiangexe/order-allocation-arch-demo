DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM wms_shipments
        WHERE cancellation_request_id IS NOT NULL
          AND cancellation_request_id !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    ) THEN
        RAISE EXCEPTION 'wms_shipments contains a cancellation_request_id that is not a UUID';
    END IF;
END $$;

ALTER TABLE wms_shipments
    DROP CONSTRAINT ck_wms_shipments_cancellation_request,
    DROP CONSTRAINT ck_wms_shipments_cancellation_outcome,
    ALTER COLUMN cancellation_request_id TYPE UUID USING cancellation_request_id::UUID;

ALTER TABLE wms_shipments
    RENAME COLUMN cancellation_outcome TO cancellation_state;

ALTER TABLE wms_shipments
    ADD COLUMN cancellation_requested_at TIMESTAMPTZ,
    ADD COLUMN cancellation_reason VARCHAR(512),
    ADD COLUMN cancelled_at TIMESTAMPTZ;

UPDATE wms_shipments
SET cancellation_state = CASE cancellation_state
        WHEN 'CANCELLED' THEN 'COMPLETED'
        WHEN 'ALREADY_CANCELLED' THEN 'COMPLETED'
        WHEN 'PUTBACK_REQUIRED' THEN 'REQUESTED'
        WHEN 'REJECTED_AFTER_HANDOVER' THEN 'REJECTED'
    END,
    cancellation_requested_at = COALESCE(cancelled_at, created_at),
    cancellation_reason = 'Migrated cancellation request',
    cancelled_at = CASE
        WHEN cancellation_state IN ('CANCELLED', 'ALREADY_CANCELLED') THEN created_at
        ELSE NULL
    END
WHERE cancellation_state IS NOT NULL;

ALTER TABLE wms_shipments
    ADD CONSTRAINT ck_wms_shipments_cancellation_state CHECK (
        cancellation_state IS NULL OR cancellation_state IN ('REQUESTED', 'COMPLETED', 'REJECTED')),
    ADD CONSTRAINT ck_wms_shipments_cancellation_metadata CHECK (
        (cancellation_state IS NULL
            AND cancellation_request_id IS NULL
            AND cancellation_requested_at IS NULL
            AND cancellation_reason IS NULL
            AND cancelled_at IS NULL)
        OR
        (cancellation_state IN ('REQUESTED', 'REJECTED')
            AND cancellation_request_id IS NOT NULL
            AND cancellation_requested_at IS NOT NULL
            AND cancellation_reason IS NOT NULL
            AND cancelled_at IS NULL)
        OR
        (cancellation_state = 'COMPLETED'
            AND cancellation_request_id IS NOT NULL
            AND cancellation_requested_at IS NOT NULL
            AND cancellation_reason IS NOT NULL
            AND cancelled_at IS NOT NULL)),
    ADD CONSTRAINT ck_wms_shipments_cancellation_matches_status CHECK (
        cancellation_state IS NULL
        OR (cancellation_state = 'REQUESTED' AND status = 'CANCELLING')
        OR (cancellation_state = 'COMPLETED' AND status = 'CANCELLED')
        OR (cancellation_state = 'REJECTED' AND status = 'HANDED_OVER_TO_CARRIER'));
