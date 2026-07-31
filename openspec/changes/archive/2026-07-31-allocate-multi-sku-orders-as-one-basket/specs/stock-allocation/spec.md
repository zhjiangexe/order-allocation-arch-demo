## ADDED Requirements

### Requirement: A multi-SKU order is satisfiable only when every one of its SKUs is

An order's demand SHALL be satisfiable only when, for **every** SKU it names, the allocatable
stock covers that SKU's aggregated quantity. One SKU falling short SHALL prevent the whole
order from allocating, and SHALL leave every other SKU's stock untouched.

This is the same rule the system has always applied — it was simply invisible while intake
permitted one line, because "every SKU" and "the SKU" were the same thing.

**The check SHALL NOT short-circuit.** Stopping at the first SKU that falls short would be
faster, but the shortfall it reports would then depend on which SKU happened to be checked
first. A caller asking "what is this order waiting for" needs all of them.

#### Scenario: One SKU short blocks the whole order

- **GIVEN** an order demanding 10 of `SKU-A` and 5 of `SKU-B`, with 100 of `SKU-A`
  allocatable and 3 of `SKU-B`
- **WHEN** the order is allocated
- **THEN** no reservation exists for either SKU, and `SKU-A`'s reserved quantity is unchanged

#### Scenario: Every SKU covered allocates the whole basket

- **GIVEN** an order demanding 10 of `SKU-A` and 5 of `SKU-B`, both fully allocatable
- **WHEN** the order is allocated
- **THEN** reservations exist for both, each attributed to its own line

#### Scenario: The shortfall names every SKU that falls short

- **GIVEN** an order demanding three SKUs of which two fall short
- **WHEN** allocation is attempted
- **THEN** the reported shortfall names both, with the missing quantity for each

---
### Requirement: Allocatable stock is supplied to allocation grouped by SKU

The batches offered to an allocation decision SHALL be grouped by SKU code, and that
grouping SHALL hold a key for **every** SKU the demand names. A demanded SKU with no key
SHALL be refused as a caller error.

**A SKU with no allocatable batch SHALL be expressed as an empty group, not as an absent
key.** The two mean different things: an empty group is ordinary stock-out, an absent key is
the caller having assembled the wrong input. Collapsing them makes a programming error
indistinguishable from a business outcome — "the batches for that SKU were never loaded"
would look exactly like "that SKU is sold out", and only one of those is a bug.

**Covering, not exactly equal.** Waking a queue supplies the union of every candidate's SKUs,
so an order naming only one of them legitimately sees keys it does not use. Extra keys are
harmless: allocation draws only on the groups its lines name, so a batch nobody asked for is
never touched. The dangerous direction is the missing one.

#### Scenario: A grouping missing one of the demanded SKUs is refused

- **GIVEN** an order naming two SKUs
- **WHEN** allocation is attempted with batches grouped for only one of them
- **THEN** the attempt is refused as an error, and no stock is modified

#### Scenario: A grouping carrying SKUs this order does not name is accepted

- **GIVEN** a queued order naming one SKU, woken in a round whose candidates together name three
- **WHEN** allocation is attempted with all three groups
- **THEN** the order is judged on its own SKU, and the other two groups are untouched

#### Scenario: A SKU with no allocatable batch is an ordinary stock-out

- **GIVEN** an order naming two SKUs, one of which has an empty batch group
- **WHEN** allocation is attempted
- **THEN** the order is not allocated, no stock is modified, and the outcome is a stock-out
  rather than an error

---
### Requirement: Waking a queue loads every SKU its candidates need

Replenishment SHALL identify its candidate orders from the replenished SKU, then load the
allocatable batches for **every** SKU those candidates name — not only the replenished one.
An order needing a SKU that was not replenished SHALL still be judged against that SKU's
current stock.

The set of stock rows a wake round will touch SHALL be fully known before the transaction
begins. The deadlock-avoiding write order can only be computed over a known set, and loading
batches while allocating would leave it undetermined until halfway through.

The number of queries SHALL NOT grow with the number of candidates.

#### Scenario: A candidate's other SKU is judged against its own stock

- **GIVEN** a queued order demanding one unit each of `SKU-A` and `SKU-B`, and no `SKU-B` in
  stock
- **WHEN** `SKU-A` is replenished
- **THEN** the order is not allocated, and the replenished `SKU-A` remains unreserved

#### Scenario: A blocked candidate stops the round rather than being skipped

- **GIVEN** two queued orders, the first demanding `SKU-A` and `SKU-B`, the second demanding
  only `SKU-A`, with `SKU-A` plentiful and `SKU-B` absent
- **WHEN** `SKU-A` is replenished
- **THEN** neither order is allocated

The second order SHALL NOT be allocated ahead of the first. Head-of-line blocking is what
first-come-first-served means; the only thing that changed is that "cannot be filled" may now
be due to a SKU other than the replenished one, and that makes no difference to the orders
queued behind.
