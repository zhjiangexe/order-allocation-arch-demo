## MODIFIED Requirements

### Requirement: The active partition key strategy is observable

A read-only endpoint SHALL report the partition-key strategy currently in effect,
so an operator can tell whether the running system is using the order-identifier
strategy or the stock strategy. The endpoint SHALL only report the value; it SHALL
NOT offer to change it, because the strategy is resolved at application startup.

The endpoint SHALL report the configured value verbatim rather than a label derived from
it. A derived label can disagree with the setting; the raw value cannot, and it is what
an operator would grep the configuration for.

#### Scenario: The configured strategy is reported

- **GIVEN** the application started with the stock partition-key strategy
- **WHEN** the configuration endpoint is queried
- **THEN** the response reports `stock` as the effective value
