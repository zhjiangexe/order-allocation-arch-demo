## MODIFIED Requirements

### Requirement: An owner is the party whose goods the warehouse holds

An owner SHALL carry a human-readable code, a name, and an operational status.

The name SHALL be stored rather than derived, because every screen and log line that
mentions an owner needs to show something other than an identifier.

#### Scenario: An owner records its identity and status

- **WHEN** an owner is queried
- **THEN** the response carries its code, its name, and its status

---

### Requirement: Seed data reproduces the collisions and contrasts later work depends on

Seed data SHALL include two owners that both define the same SKU code. It SHALL include
one ambient product and one frozen product, and at least one product carrying two
specifications of different weights.

Each element exists to make a later decision observable: the shared SKU code is the
minimal reproduction of cross-owner collision, and the two specifications make the
product-and-specification split visible on screen.

The two temperature zones SHALL remain seeded even though nothing reads temperature
today. Temperature belongs to the product as a fact about the goods, and the two-level
product-and-specification structure exists to make "one product, two temperature zones"
unrepresentable — that value does not depend on a reader.

#### Scenario: Seeded data contains a cross-owner SKU code collision

- **WHEN** the seeded catalog is inspected
- **THEN** two owners exist and one SKU code is defined by both
