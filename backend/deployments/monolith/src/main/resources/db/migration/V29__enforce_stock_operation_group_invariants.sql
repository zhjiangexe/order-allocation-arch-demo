-- Final validation and deferred cross-row guards. Picking state is a checked summary of move state;
-- move lines are the only current reservation/execution detail.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM stock_pickings picking
         WHERE NOT EXISTS (
               SELECT 1
                 FROM stock_moves move
                WHERE move.picking_id = picking.id)
    ) OR EXISTS (
        SELECT 1
          FROM stock_pickings picking
          JOIN stock_moves move ON move.picking_id = picking.id
         WHERE move.state <> picking.state
    ) THEN
        RAISE EXCEPTION 'stock operation invariant failed: picking is empty or states are not homogeneous';
    END IF;

    IF EXISTS (
        SELECT move.id
          FROM stock_moves move
          LEFT JOIN stock_move_lines move_line ON move_line.move_id = move.id
         GROUP BY move.id, move.state, move.demand_quantity
        HAVING (move.state IN ('CONFIRMED', 'CANCELLED') AND COUNT(move_line.id) <> 0)
            OR (move.state IN ('ASSIGNED', 'DONE')
                AND COALESCE(SUM(move_line.quantity), 0) <> move.demand_quantity)
    ) THEN
        RAISE EXCEPTION 'stock operation invariant failed: move-line coverage mismatch';
    END IF;
END $$;

CREATE FUNCTION assert_stock_picking_group(target_picking_id UUID)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    target_state VARCHAR(32);
BEGIN
    SELECT state
      INTO target_state
      FROM stock_pickings
     WHERE id = target_picking_id;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM stock_moves
         WHERE picking_id = target_picking_id
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = 'check_violation',
            MESSAGE = 'stock picking must contain at least one move';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM stock_moves
         WHERE picking_id = target_picking_id
           AND state <> target_state
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = 'check_violation',
            MESSAGE = 'stock picking and move states must be homogeneous';
    END IF;

    IF EXISTS (
        SELECT move.id
          FROM stock_moves move
          LEFT JOIN stock_move_lines move_line ON move_line.move_id = move.id
         WHERE move.picking_id = target_picking_id
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

CREATE FUNCTION enforce_stock_picking_group()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    old_picking_id UUID;
    new_picking_id UUID;
BEGIN
    IF TG_TABLE_NAME = 'stock_pickings' THEN
        IF TG_OP <> 'INSERT' THEN
            old_picking_id := OLD.id;
        END IF;
        IF TG_OP <> 'DELETE' THEN
            new_picking_id := NEW.id;
        END IF;
    ELSIF TG_TABLE_NAME = 'stock_moves' THEN
        IF TG_OP <> 'INSERT' THEN
            old_picking_id := OLD.picking_id;
        END IF;
        IF TG_OP <> 'DELETE' THEN
            new_picking_id := NEW.picking_id;
        END IF;
    ELSE
        IF TG_OP <> 'INSERT' THEN
            SELECT picking_id INTO old_picking_id FROM stock_moves WHERE id = OLD.move_id;
        END IF;
        IF TG_OP <> 'DELETE' THEN
            SELECT picking_id INTO new_picking_id FROM stock_moves WHERE id = NEW.move_id;
        END IF;
    END IF;

    IF old_picking_id IS NOT NULL THEN
        PERFORM assert_stock_picking_group(old_picking_id);
    END IF;
    IF new_picking_id IS NOT NULL AND new_picking_id IS DISTINCT FROM old_picking_id THEN
        PERFORM assert_stock_picking_group(new_picking_id);
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER ck_stock_pickings_move_group
    AFTER INSERT OR UPDATE OR DELETE
    ON stock_pickings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION enforce_stock_picking_group();

CREATE CONSTRAINT TRIGGER ck_stock_moves_picking_group
    AFTER INSERT OR UPDATE OR DELETE
    ON stock_moves
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION enforce_stock_picking_group();

CREATE CONSTRAINT TRIGGER ck_stock_move_lines_picking_group
    AFTER INSERT OR UPDATE OR DELETE
    ON stock_move_lines
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION enforce_stock_picking_group();

CREATE FUNCTION assert_stock_pool_reservation(target_stock_pool_id UUID)
RETURNS VOID
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

CREATE FUNCTION enforce_stock_pool_reservation()
RETURNS TRIGGER
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

CREATE CONSTRAINT TRIGGER ck_stock_pools_reserved_move_lines
    AFTER INSERT OR UPDATE OF reserved_quantity
    ON stock_pools
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION enforce_stock_pool_reservation();

CREATE CONSTRAINT TRIGGER ck_stock_move_lines_reserved_pool
    AFTER INSERT OR UPDATE OR DELETE
    ON stock_move_lines
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION enforce_stock_pool_reservation();

CREATE CONSTRAINT TRIGGER ck_stock_moves_reserved_pool
    AFTER UPDATE OF state
    ON stock_moves
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION enforce_stock_pool_reservation();
