# stock-locations Specification

## Purpose

TBD - created by archiving change 'hold-stock-in-locations'. Update Purpose after archive.

## Requirements

### Requirement: A location's usage decides whether its contents are the company's stock

A stock location SHALL carry a usage drawn from a fixed set: `internal`, `supplier`,
`customer`, `inventory`. Only an `internal` location SHALL count towards what the company
holds; the other three are virtual — they exist so that every movement of goods has a
place at both ends.

Without them, receiving and shipping cannot be expressed as movements at all. Goods
arriving from a vendor come *from* somewhere, and that somewhere is not a warehouse this
system operates; goods shipped to a customer go *to* somewhere with the same property.
Modelling both as movements between locations is what makes the total quantity across
all locations conserved, and conservation is what makes an unexplained change detectable.

**The usage SHALL be a constrained value on the location, not a reference to a table of
usage types.** The set is not configuration — each value is a branch in program logic:
only `internal` counts as stock, and `internal` is the sole usage an order's source can
name.
A configurable set would make adding a fifth usage look like data maintenance when in
fact every branch would have to change with it.

#### Scenario: An internal location's contents count as stock

- **GIVEN** goods are held in a location whose usage is `internal`
- **WHEN** what the owner holds is queried
- **THEN** those goods are reported

#### Scenario: A usage outside the fixed set is rejected

- **WHEN** a location is written with a usage outside `internal`, `supplier`, `customer`,
  `inventory`
- **THEN** the write is rejected by the database rather than accepted and interpreted later

---
### Requirement: Every warehouse has exactly one internal location, and virtual locations have none

Each warehouse SHALL have exactly one `internal` location, and that location SHALL
reference its warehouse. Each virtual location SHALL reference no warehouse.

One internal location per warehouse is what this system needs today: it does not model
receiving areas, put-away zones, or shipping staging. A location tree is deliberately not
introduced — see below.

**Locations SHALL NOT form a hierarchy.** A parent reference exists in warehouse systems
to express storage zones and to find a location's warehouse by walking upwards. Here the
warehouse reference answers that question directly, and no query walks a tree. If
receiving or staging areas are needed later, they arrive as additional locations under the
same warehouse; orders already name a location and owner assignments already name a
warehouse, so neither has to change.

**Owner-to-warehouse assignment SHALL remain on the warehouse.** Which warehouses an owner
ships from is a commercial fact that holds before any goods arrive — it is not a property
of a place inside the building. Were it moved to locations, a warehouse with five
locations would have no correct answer to whether the owner is assigned to all five or
only to the one holding stock.

#### Scenario: A warehouse resolves to its internal location

- **WHEN** the location of a given warehouse is resolved
- **THEN** exactly one `internal` location is found, and it references that warehouse

#### Scenario: Virtual locations belong to no warehouse

- **WHEN** the `supplier`, `customer`, and `inventory` locations are queried
- **THEN** each exists exactly once and none references a warehouse

---
### Requirement: An order names a warehouse and never a location

An order SHALL continue to name the warehouse it ships from and SHALL NOT carry a
location. Where a location is needed — to find the stock an order can draw on — it SHALL
be resolved from that warehouse.

An order is a statement of demand, not an instruction to move goods. Which place inside a
warehouse the goods leave from is settled when the movement is planned, and movements do
not exist yet. Putting a location on the order now would also break a guarantee the
database currently makes: the order's composite foreign key onto the owner-to-warehouse
assignment is what rejects an order naming a warehouse its owner is not assigned to, and
a location carries no owner for that key to reach.

**A warehouse SHALL resolve to exactly one internal location**, so the resolution is a
lookup rather than a choice. When several internal locations per warehouse arrive, the
choice belongs to the operation type that plans the movement — not to the order.

#### Scenario: An order still records the warehouse it was given

- **WHEN** an order is placed naming a warehouse
- **THEN** the order records that warehouse, carries no location, and querying it reports
  the warehouse that was named

#### Scenario: An order for a warehouse its owner is not assigned to is still refused

- **GIVEN** a warehouse that exists but is not assigned to a given owner
- **WHEN** that owner places an order naming it
- **THEN** the order is refused, as it was before locations existed

---
### Requirement: Locations expose no write interface

The system SHALL NOT expose any HTTP endpoint that creates, updates, or deletes a
location. Locations follow the warehouses they belong to: reference data maintained
upstream, delivered to the demo through seed data.

#### Scenario: Location data cannot be mutated over HTTP

- **WHEN** any HTTP method other than a read is directed at a location path
- **THEN** no location is created, changed, or removed

---
### Requirement: Seed data provides one internal location per seeded warehouse and all three virtual locations

Seed data SHALL create one `internal` location for each seeded warehouse and exactly one
location for each of the three virtual usages.

The virtual locations have no reader in this capability — nothing moves goods yet. They
are seeded now because the usage set must be settled in one place: introducing them later
would mean altering the constraint and revisiting seed data at the same time as
introducing movements, which would put two independent failure modes in one change.

#### Scenario: Seeded locations cover every warehouse and every virtual usage

- **WHEN** the seeded locations are inspected
- **THEN** each seeded warehouse has exactly one `internal` location, and exactly one
  location exists for each of `supplier`, `customer`, and `inventory`
