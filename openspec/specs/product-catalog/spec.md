# product-catalog Specification

## Purpose

TBD - created by archiving change 'add-owner-and-order-line-model'. Update Purpose after archive.

## Requirements

### Requirement: An owner is the party whose goods the warehouse holds

An owner SHALL carry a human-readable code, a name, an operational status, and whether
that owner permits a single order to ship from more than one node.

The name SHALL be stored rather than derived, because every screen and log line that
mentions an owner needs to show something other than an identifier.

The split-shipment permission SHALL be stored on the owner because it is a term of the
logistics agreement, settled once per owner, rather than a decision taken per order.
No behavior in this capability reads it yet.

#### Scenario: An owner records its identity, status, and split-shipment permission

- **WHEN** an owner is queried
- **THEN** the response carries its code, its name, its status, and whether it permits
  split shipment

---
### Requirement: Catalog identifiers are scoped to their owner

A product code SHALL be unique only within its owner, and a SKU code SHALL be unique
only within its owner. Neither code SHALL be unique on its own, and neither SHALL
identify anything when named without an owner.

In third-party logistics each owner assigns its own codes, so the same code identifies
different goods for different owners. Every reference to a catalog entry from outside
the catalog SHALL therefore name the owner as well, making the collision impossible to
overlook rather than merely documented.

How a catalog entry is keyed internally is not constrained here — only that the
owner-scoped uniqueness is enforced by storage rather than by convention.

#### Scenario: The same SKU code under two owners denotes two distinct SKUs

- **GIVEN** owner A and owner B each define SKU code `SKU-A`
- **WHEN** each owner's `SKU-A` is queried
- **THEN** two distinct SKUs are returned, each with its own product, specification
  name, and weight

##### Example: catalog entries that collide by code

| Owner | SKU code | Product | Specification | Weight (g) |
| --- | --- | --- | --- | --- |
| A | `SKU-A` | ambient tea | 500ml | 520 |
| B | `SKU-A` | frozen dumplings | 1kg | 1000 |

---
### Requirement: Temperature zone belongs to the product, weight belongs to the SKU

A product SHALL carry the temperature zone its goods require. A SKU SHALL carry its
weight in grams, which SHALL be positive.

Temperature zone SHALL NOT be held on the SKU. Holding it there would permit two
specifications of one product to declare different zones — a state that is not merely
invalid but undetectable at intake, surfacing only much later when node capabilities
are matched against it.

Weight SHALL NOT be held on the product, because specifications of one product differ
in weight and weight is a shipping-cost input.

#### Scenario: Two specifications of one product share its temperature zone

- **GIVEN** one product with two SKUs of different weights
- **WHEN** each SKU is queried
- **THEN** both report the temperature zone of their shared product, and each reports
  its own weight

---
### Requirement: The catalog is queryable from owner to product to SKU

The catalog SHALL support listing all owners, listing one owner's products, and
listing one product's SKUs. These queries exist so that an order can be composed by
selecting an owner, then a product, then a specification.

#### Scenario: Selecting downward yields the specifications of one product

- **WHEN** owners are listed, one owner's products are listed, and one of those
  products' SKUs are listed
- **THEN** each step returns only entries belonging to the selection made in the
  previous step

---
### Requirement: The catalog exposes no write interface

The catalog SHALL expose queries only. Owners, products, and SKUs SHALL be established
as seed data. No endpoint or use case SHALL create, update, or delete them.

Master-data maintenance is a separate concern with its own authorisation and audit
requirements; providing a partial version of it here would invite reliance on an
interface that is not designed for that purpose.

#### Scenario: The catalog offers no way to create or modify an entry

- **WHEN** the catalog's interface is enumerated
- **THEN** it contains only queries, and no operation creates, updates, or deletes an
  owner, a product, or a SKU

---
### Requirement: Seed data reproduces the collisions and contrasts later work depends on

Seed data SHALL include two owners that differ in their split-shipment permission and
that both define the same SKU code. It SHALL include one ambient product and one
frozen product, and at least one product carrying two specifications of different
weights.

Each element exists to make a later decision observable: the shared SKU code is the
minimal reproduction of cross-owner collision, the differing permissions are the
strongest contrast for split-shipment decisions, the two temperature zones are needed
before node capability filtering can be seen to filter anything, and the two
specifications make the product-and-specification split visible on screen.

#### Scenario: Seeded data contains a cross-owner SKU code collision

- **WHEN** the seeded catalog is inspected
- **THEN** two owners exist with different split-shipment permissions, and one SKU code
  is defined by both
