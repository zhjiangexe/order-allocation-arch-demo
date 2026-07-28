# warehouse-catalog Specification

## Purpose

TBD - created by archiving change 'add-warehouse-and-owner-assignment'. Update Purpose after archive.

## Requirements

### Requirement: A warehouse is a place goods ship from, not a place the system chooses

A warehouse SHALL carry a human-readable code and a name, and nothing else. The system
SHALL NOT hold a warehouse type, an operational status, a delivery coverage, a handling
capability, a daily capacity, or a cut-off time.

Every one of those attributes existed to serve a decision the system does not make.
Which warehouse an order ships from is settled by the logistics agreement and supplied
by the owner's upstream system; the system records that choice and never revisits it.
An attribute with no reader is worse than a missing one, because the next reader assumes
it means something.

#### Scenario: A warehouse exposes only its identity

- **WHEN** a warehouse is queried
- **THEN** the response carries its identifier, its code, and its name, and carries no
  status, capability, capacity, or coverage

---
### Requirement: An owner ships from an explicitly assigned set of warehouses

An owner SHALL be assigned to one or more warehouses, and a warehouse SHALL serve one
or more owners. The assignment SHALL be recorded explicitly rather than inferred from
where that owner happens to hold stock, because an owner is assigned to a warehouse
before any goods arrive there.

The assignment SHALL carry no settings in this capability. Settings that vary by owner
and warehouse — whether batches may be mixed within one shipment, for instance — belong
to the capability that reads them.

#### Scenario: An owner assigned to two warehouses lists both

- **WHEN** the warehouses available to an owner are queried
- **THEN** the response lists exactly the warehouses that owner is assigned to, in a
  stable order

#### Scenario: An owner is not offered a warehouse it is not assigned to

- **GIVEN** a warehouse exists that a given owner is not assigned to
- **WHEN** the warehouses available to that owner are queried
- **THEN** that warehouse is absent from the response

##### Example: assignments and what each owner sees

| Warehouse | Assigned to | `OWNER-A` sees | `OWNER-B` sees |
| --- | --- | --- | --- |
| `WH-NORTH` | `OWNER-A` | ✓ | — |
| `WH-CENTRAL` | `OWNER-A`, `OWNER-B` | ✓ | ✓ |
| `WH-SOUTH` | `OWNER-B` | — | ✓ |

`OWNER-A` is offered two warehouses and `OWNER-B` one; the sets overlap without being
equal, so neither "every owner sees everything" nor "owners never share a warehouse"
can pass by accident.

---
### Requirement: The warehouse catalog exposes no write interface

The system SHALL NOT expose any HTTP endpoint that creates, updates, or deletes a
warehouse or an owner's warehouse assignment. Both are reference data maintained
upstream; the demo receives them through seed data.

#### Scenario: Warehouse data cannot be mutated over HTTP

- **WHEN** any HTTP method other than a read is directed at a warehouse path
- **THEN** no warehouse and no assignment is created, changed, or removed

---
### Requirement: Seed data makes both warehouse relationships visible

Seed data SHALL include three warehouses and two owners, each owner assigned to two
warehouses, sharing exactly one between them.

Three facts have to be observable at once: that a single owner ships from more than one
warehouse, that two owners do not see the same list, and that **one warehouse serves
more than one owner**. The third is what makes this a third-party warehouse rather than
an owner's own; a seed where every warehouse belongs to exactly one owner would let a
filter that keys on the warehouse instead of the assignment pass unnoticed.

Overlapping-but-unequal sets are the smallest arrangement satisfying all three.

#### Scenario: Seeded assignments overlap without being equal

- **WHEN** the seeded warehouse assignments are inspected
- **THEN** three warehouses exist, each owner is assigned to two of them, exactly one
  warehouse is assigned to both owners, and the two owners' assigned sets are not equal
