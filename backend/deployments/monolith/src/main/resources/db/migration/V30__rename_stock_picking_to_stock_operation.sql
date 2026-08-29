-- Forward-only metadata rename. UUIDs, rows, lifecycle values and execution semantics are preserved.
-- Historical migrations and retained event payloads intentionally keep their published vocabulary.

DROP VIEW stock_order_movement_sources;

DROP TRIGGER ck_stock_pickings_move_group ON stock_pickings;
DROP TRIGGER ck_stock_moves_picking_group ON stock_moves;
DROP TRIGGER ck_stock_move_lines_picking_group ON stock_move_lines;
DROP FUNCTION enforce_stock_picking_group();
DROP FUNCTION assert_stock_picking_group(UUID);

ALTER TABLE stock_picking_types RENAME TO stock_operation_types;
ALTER TABLE stock_pickings RENAME TO stock_operations;
ALTER TABLE stock_picking_cancellation_operations RENAME TO stock_operation_cancellations;

ALTER TABLE stock_operations
    RENAME COLUMN picking_type_id TO stock_operation_type_id;

ALTER TABLE stock_moves
    RENAME COLUMN picking_id TO stock_operation_id;

ALTER TABLE stock_operation_cancellations
    RENAME COLUMN picking_id TO stock_operation_id;

ALTER TABLE stock_operation_cancellations
    RENAME COLUMN operation_id TO cancellation_operation_id;

ALTER TABLE wms_shipments
    RENAME COLUMN picking_id TO stock_operation_id;

ALTER TABLE stock_operation_types
    RENAME CONSTRAINT stock_picking_types_pkey TO stock_operation_types_pkey;
ALTER TABLE stock_operation_types
    RENAME CONSTRAINT uq_stock_picking_types_facility_code TO uq_stock_operation_types_facility_code;
ALTER TABLE stock_operation_types
    RENAME CONSTRAINT fk_stock_picking_types_facility TO fk_stock_operation_types_facility;
ALTER TABLE stock_operation_types
    RENAME CONSTRAINT fk_stock_picking_types_from_location TO fk_stock_operation_types_from_location;
ALTER TABLE stock_operation_types
    RENAME CONSTRAINT fk_stock_picking_types_to_location TO fk_stock_operation_types_to_location;
ALTER TABLE stock_operation_types
    RENAME CONSTRAINT ck_stock_picking_types_code TO ck_stock_operation_types_code;

ALTER TABLE stock_operations
    RENAME CONSTRAINT stock_pickings_pkey TO stock_operations_pkey;
ALTER TABLE stock_operations
    RENAME CONSTRAINT fk_stock_pickings_type TO fk_stock_operations_type;
ALTER TABLE stock_operations
    RENAME CONSTRAINT fk_stock_pickings_owner TO fk_stock_operations_owner;
ALTER TABLE stock_operations
    RENAME CONSTRAINT fk_stock_pickings_from_location TO fk_stock_operations_from_location;
ALTER TABLE stock_operations
    RENAME CONSTRAINT fk_stock_pickings_to_location TO fk_stock_operations_to_location;
ALTER TABLE stock_operations
    RENAME CONSTRAINT ck_stock_pickings_state TO ck_stock_operations_state;
ALTER TABLE stock_operations
    RENAME CONSTRAINT ck_stock_pickings_direction TO ck_stock_operations_direction;
ALTER TABLE stock_operations
    RENAME CONSTRAINT ck_stock_pickings_scheduling_by_direction TO ck_stock_operations_scheduling_by_direction;
ALTER TABLE stock_operations
    RENAME CONSTRAINT ck_stock_pickings_source_identity TO ck_stock_operations_source_identity;
ALTER TABLE stock_operations
    RENAME CONSTRAINT ck_stock_pickings_source_type TO ck_stock_operations_source_type;

ALTER TABLE stock_moves
    RENAME CONSTRAINT fk_stock_moves_picking TO fk_stock_moves_stock_operation;

ALTER TABLE stock_operation_cancellations
    RENAME CONSTRAINT stock_picking_cancellation_operations_pkey TO stock_operation_cancellations_pkey;
ALTER TABLE stock_operation_cancellations
    RENAME CONSTRAINT fk_stock_picking_cancellation_operations_picking
    TO fk_stock_operation_cancellations_stock_operation;
ALTER TABLE stock_operation_cancellations
    RENAME CONSTRAINT ck_stock_picking_cancellation_operations_state TO ck_stock_operation_cancellations_state;

ALTER TABLE wms_shipments
    RENAME CONSTRAINT uq_wms_shipments_picking TO uq_wms_shipments_stock_operation;

ALTER INDEX uq_stock_pickings_source_unit RENAME TO uq_stock_operations_source_unit;
ALTER INDEX idx_stock_pickings_confirmed_scope_queue RENAME TO idx_stock_operations_confirmed_scope_queue;
ALTER INDEX uq_stock_moves_picking_source_line RENAME TO uq_stock_moves_operation_source_line;
ALTER INDEX uq_stock_moves_picking_line_sequence RENAME TO uq_stock_moves_operation_line_sequence;
ALTER INDEX idx_stock_moves_picking_ordered RENAME TO idx_stock_moves_operation_ordered;
ALTER INDEX idx_stock_moves_confirmed_picking_sku RENAME TO idx_stock_moves_confirmed_operation_sku;

CREATE VIEW stock_order_movement_sources AS
SELECT o.id AS source_id,
       o.owner_id,
       o.facility_id,
       operation_type.id AS stock_operation_type_id,
       operation_type.default_from_location_id AS source_location_id,
       operation_type.default_to_location_id AS destination_location_id,
       o.dispatch_by AS required_by,
       o.release_priority,
       o.received_at AS enqueued_at,
       ol.id AS source_line_reference_id,
       ol.id::text AS source_line_id,
       ol.sku_code,
       ol.quantity
  FROM orders o
  JOIN order_lines ol ON ol.order_id = o.id
  LEFT JOIN stock_operation_types operation_type
    ON operation_type.facility_id = o.facility_id
   AND operation_type.code = 'OUTBOUND'
 WHERE o.cancelled_at IS NULL;

CREATE FUNCTION assert_stock_operation(target_stock_operation_id UUID)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    target_state VARCHAR(32);
BEGIN
    SELECT state
      INTO target_state
      FROM stock_operations
     WHERE id = target_stock_operation_id;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM stock_moves
         WHERE stock_operation_id = target_stock_operation_id
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = 'check_violation',
            MESSAGE = 'stock operation must contain at least one move';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM stock_moves
         WHERE stock_operation_id = target_stock_operation_id
           AND state <> target_state
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = 'check_violation',
            MESSAGE = 'stock operation and move states must be homogeneous';
    END IF;

    IF EXISTS (
        SELECT move.id
          FROM stock_moves move
          LEFT JOIN stock_move_lines move_line ON move_line.move_id = move.id
         WHERE move.stock_operation_id = target_stock_operation_id
         GROUP BY move.id, move.state, move.demand_quantity
        HAVING (move.state IN ('CONFIRMED', 'CANCELLED') AND COUNT(move_line.id) <> 0)
            OR (move.state IN ('ASSIGNED', 'DONE')
                AND COALESCE(SUM(move_line.quantity), 0) <> move.demand_quantity)
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = 'check_violation',
            MESSAGE = 'stock move lines must exactly match the move lifecycle and demand quantity';
    END IF;
END $$;

CREATE FUNCTION enforce_stock_operation_group()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    old_stock_operation_id UUID;
    new_stock_operation_id UUID;
BEGIN
    IF TG_TABLE_NAME = 'stock_operations' THEN
        IF TG_OP <> 'INSERT' THEN
            old_stock_operation_id := OLD.id;
        END IF;
        IF TG_OP <> 'DELETE' THEN
            new_stock_operation_id := NEW.id;
        END IF;
    ELSIF TG_TABLE_NAME = 'stock_moves' THEN
        IF TG_OP <> 'INSERT' THEN
            old_stock_operation_id := OLD.stock_operation_id;
        END IF;
        IF TG_OP <> 'DELETE' THEN
            new_stock_operation_id := NEW.stock_operation_id;
        END IF;
    ELSE
        IF TG_OP <> 'INSERT' THEN
            SELECT stock_operation_id
              INTO old_stock_operation_id
              FROM stock_moves
             WHERE id = OLD.move_id;
        END IF;
        IF TG_OP <> 'DELETE' THEN
            SELECT stock_operation_id
              INTO new_stock_operation_id
              FROM stock_moves
             WHERE id = NEW.move_id;
        END IF;
    END IF;

    IF old_stock_operation_id IS NOT NULL THEN
        PERFORM assert_stock_operation(old_stock_operation_id);
    END IF;
    IF new_stock_operation_id IS NOT NULL
       AND new_stock_operation_id IS DISTINCT FROM old_stock_operation_id THEN
        PERFORM assert_stock_operation(new_stock_operation_id);
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER ck_stock_operations_move_group
    AFTER INSERT OR UPDATE OR DELETE
    ON stock_operations
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION enforce_stock_operation_group();

CREATE CONSTRAINT TRIGGER ck_stock_moves_operation_group
    AFTER INSERT OR UPDATE OR DELETE
    ON stock_moves
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION enforce_stock_operation_group();

CREATE CONSTRAINT TRIGGER ck_stock_move_lines_operation_group
    AFTER INSERT OR UPDATE OR DELETE
    ON stock_move_lines
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION enforce_stock_operation_group();
