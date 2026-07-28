## MODIFIED Requirements

### Requirement: An order carries an owner, an upstream reference, and a delivery commitment

An order SHALL identify the owner whose goods it draws on, the order number assigned
by that owner's upstream system, **the warehouse it ships from**, the destination zone,
the destination address, and the promised delivery date. The destination zone and the
destination address SHALL be separate fields because they serve different consumers:
the address is a fulfillment and label input, the zone is retained as the coarse form
of the destination.

The shipping warehouse SHALL be required. It is supplied by the owner's upstream system
at order creation and the system SHALL NOT derive, default, or revisit it. A missing
warehouse SHALL be rejected rather than resolved.

The address SHALL be held on the order itself rather than in a separate address
entity, because an address is specified per order and is never reused.

#### Scenario: A placed order records owner, upstream reference, warehouse, and destination

- **WHEN** an order is placed with an owner, an upstream order number, a warehouse, a
  destination zone, a destination address, and a promised delivery date
- **THEN** all six are persisted with the order and are returned when that order is
  queried

#### Scenario: An order without a warehouse is rejected

- **WHEN** an order is placed without naming a warehouse
- **THEN** the order is rejected and no order and no line are persisted

---

### Requirement: An order line references an existing catalog entry

A line's owner and SKU code together SHALL reference an existing SKU in the catalog.
An order naming a SKU code that the catalog does not hold for that owner SHALL be
rejected and SHALL NOT be persisted.

An order's owner and warehouse together SHALL reference an assignment that exists. An
order naming a warehouse that its owner is not assigned to SHALL be rejected and SHALL
NOT be persisted.

Both SHALL be enforced by the storage layer's referential integrity rather than by a
prior application-level lookup, so that no order can reach storage through a path that
skips the check.

#### Scenario: An order naming an unknown SKU is rejected

- **WHEN** an order is placed whose line names a SKU code that the catalog does not
  hold for that order's owner
- **THEN** the order is rejected and no order and no line are persisted

#### Scenario: An order naming a warehouse its owner is not assigned to is rejected

- **WHEN** an order is placed naming a warehouse that exists but that the order's owner
  is not assigned to
- **THEN** the order is rejected and no order and no line are persisted

##### Example: which orders reach storage

| Owner | Warehouse named | SKU code named | Result |
| --- | --- | --- | --- |
| `OWNER-A` | one `OWNER-A` is assigned to | one `OWNER-A` defines | persisted |
| `OWNER-A` | none | one `OWNER-A` defines | rejected, nothing persisted |
| `OWNER-A` | one only `OWNER-B` is assigned to | one `OWNER-A` defines | rejected, nothing persisted |
| `OWNER-A` | one that does not exist | one `OWNER-A` defines | rejected, nothing persisted |
| `OWNER-A` | one `OWNER-A` is assigned to | one only `OWNER-B` defines | rejected, nothing persisted |

The third and fifth rows are the same mistake in two dimensions: naming something that
exists but belongs to another owner. Neither is caught by existence alone.

## ADDED Requirements

### Requirement: A line inherits its order's warehouse rather than carrying its own

An order line SHALL NOT carry a shipping warehouse. One order ships from one warehouse
and its lines cannot span warehouses, so a warehouse held on the line would be a copy
of the header that can never differ.

This SHALL remain true when orders are allowed to carry several lines. Several lines
mean several SKUs on one order, not several warehouses; per-line warehouses would only
be needed to split an order across warehouses, which the system does not do.

The line's denormalized owner SHALL be retained, because unlike the warehouse it earns
its place: the owner and SKU code together form the catalog's natural key, and the
foreign key cannot be expressed without it.

#### Scenario: A line reports the warehouse of its order

- **WHEN** an order's lines are queried
- **THEN** no line carries a warehouse of its own, and the order's warehouse applies to
  every line
