## MODIFIED Requirements

### Requirement: Demonstrate a real optimistic-lock conflict

The scenario SHALL produce genuine optimistic-lock conflicts on stock, observed through
the retry counters rather than asserted from a fabricated barrier.

**Contention SHALL be concentrated on a single stock row.** With stock split by owner,
warehouse, arrival and expiry, the same burst spread over several rows contends far
less — the scenario would still pass while measuring something weaker than it did
before. The seeded stock for this scenario SHALL therefore be one row, and the
scenario SHALL assert that it is one row, so the concentration cannot be lost by an
unrelated change to seed data.

#### Scenario: A burst against one stock row produces retries

- **GIVEN** the SKU under test holds its entire quantity in a single stock row
- **WHEN** many orders for it are submitted concurrently
- **THEN** the retry counters record conflicts, and the final allocated total does not
  exceed that row's on-hand quantity

#### Scenario: The scenario fails loudly if stock is no longer concentrated

- **WHEN** the scenario runs against stock held in more than one row
- **THEN** it fails rather than passing with reduced contention
