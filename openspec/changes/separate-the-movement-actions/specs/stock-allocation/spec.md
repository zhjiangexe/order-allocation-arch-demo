## ADDED Requirements

### Requirement: Every writer of stock rows uses one global order

Every code path that writes stock rows SHALL write them sorted by one order defined in a
single place, and that order SHALL be a total order over the rows.

Two transactions that lock the same rows in opposite orders wait for each other forever.
The only defence is that every writer agrees on one sequence, which means the sequence
cannot be each writer's own — it has to be one definition they all reach for.

Until now there was one writer, and the order lived inside it as a private detail. There are
now three paths that touch stock: assigning it, releasing it, and — when shipping arrives —
completing it. A copy of the comparator in each is the shape this fails in, because the
copies drift and the symptom is a deadlock under concurrency: not reproducible on demand,
absent from load tests, present in production.

The order SHALL be computed over a set known before the transaction begins, and SHALL NOT
rely on the incidental ordering of any query. The batch query happens to return rows in a
compatible order; releasing and waking assemble their sets from entirely different sources.

#### Scenario: Two paths write the same rows in the same sequence

- **GIVEN** two stock rows that both assigning and releasing would touch
- **WHEN** each path writes them
- **THEN** both write them in the same sequence

#### Scenario: The sequence does not follow the order rows arrived in

- **GIVEN** rows supplied to a write in an order opposite to the global one
- **WHEN** they are written
- **THEN** they are written in the global order, not the order supplied
