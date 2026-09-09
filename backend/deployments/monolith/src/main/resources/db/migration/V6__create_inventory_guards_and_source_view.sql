-- 跨表一致性在 transaction 結束時驗證，允許同一交易內依序寫入 operation、move 與批次。
-- 這些是持續生效的業務約束，不是舊資料回填。

CREATE FUNCTION assert_stock_operation(target_stock_operation_id uuid) RETURNS void
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

CREATE FUNCTION assert_stock_pool_reservation(target_stock_pool_id uuid) RETURNS void
    LANGUAGE plpgsql
    AS $$
DECLARE
    stored_quantity INTEGER;
    derived_quantity BIGINT;
BEGIN
    SELECT reserved_quantity
      INTO stored_quantity
      FROM stock_pools
     WHERE id = target_stock_pool_id;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    SELECT COALESCE(SUM(move_line.quantity), 0)
      INTO derived_quantity
      FROM stock_move_lines move_line
      JOIN stock_moves move ON move.id = move_line.move_id
     WHERE move_line.stock_pool_id = target_stock_pool_id
       AND move.state = 'ASSIGNED';

    IF stored_quantity <> derived_quantity THEN
        RAISE EXCEPTION USING
            ERRCODE = 'check_violation',
            MESSAGE = 'stock pool reserved quantity must equal assigned move-line quantity';
    END IF;
END $$;

CREATE FUNCTION enforce_stock_operation_group() RETURNS trigger
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

CREATE FUNCTION enforce_stock_pool_reservation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    pool_id UUID;
BEGIN
    IF TG_TABLE_NAME = 'stock_pools' THEN
        PERFORM assert_stock_pool_reservation(COALESCE(NEW.id, OLD.id));
    ELSIF TG_TABLE_NAME = 'stock_move_lines' THEN
        IF TG_OP <> 'INSERT' THEN
            PERFORM assert_stock_pool_reservation(OLD.stock_pool_id);
        END IF;
        IF TG_OP <> 'DELETE' AND NEW.stock_pool_id IS DISTINCT FROM OLD.stock_pool_id THEN
            PERFORM assert_stock_pool_reservation(NEW.stock_pool_id);
        ELSIF TG_OP = 'INSERT' THEN
            PERFORM assert_stock_pool_reservation(NEW.stock_pool_id);
        END IF;
    ELSE
        FOR pool_id IN
            SELECT DISTINCT stock_pool_id
              FROM stock_move_lines
             WHERE move_id = COALESCE(NEW.id, OLD.id)
        LOOP
            PERFORM assert_stock_pool_reservation(pool_id);
        END LOOP;
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER ck_stock_move_lines_operation_group
    AFTER INSERT OR DELETE OR UPDATE ON stock_move_lines
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION enforce_stock_operation_group();

CREATE CONSTRAINT TRIGGER ck_stock_move_lines_reserved_pool
    AFTER INSERT OR DELETE OR UPDATE ON stock_move_lines
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION enforce_stock_pool_reservation();

CREATE CONSTRAINT TRIGGER ck_stock_moves_operation_group
    AFTER INSERT OR DELETE OR UPDATE ON stock_moves
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION enforce_stock_operation_group();

CREATE CONSTRAINT TRIGGER ck_stock_moves_reserved_pool
    AFTER UPDATE OF state ON stock_moves
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION enforce_stock_pool_reservation();

CREATE CONSTRAINT TRIGGER ck_stock_operations_move_group
    AFTER INSERT OR DELETE OR UPDATE ON stock_operations
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION enforce_stock_operation_group();

CREATE CONSTRAINT TRIGGER ck_stock_pools_reserved_move_lines
    AFTER INSERT OR UPDATE OF reserved_quantity ON stock_pools
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION enforce_stock_pool_reservation();

-- Ordering → Inventory 的唯讀來源 View；不保存第二份需求狀態。

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
    (ol.id)::text AS source_line_id,
    ol.sku_code,
    ol.quantity
   FROM ((orders o
     JOIN order_lines ol ON ((ol.order_id = o.id)))
     LEFT JOIN stock_operation_types operation_type ON (((operation_type.facility_id = o.facility_id) AND ((operation_type.code)::text = 'OUTBOUND'::text))))
  WHERE (o.cancelled_at IS NULL);
