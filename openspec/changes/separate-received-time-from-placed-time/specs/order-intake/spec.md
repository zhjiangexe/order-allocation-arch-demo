## ADDED Requirements

### Requirement: An order records when we received it and, when supplied, when it was placed

An order SHALL record the instant this system received and accepted it. That instant SHALL
be written by this system, SHALL NOT be supplied by the caller, and SHALL NOT be null.

An order SHALL additionally record the instant the upstream system says the customer placed
it. That instant SHALL be supplied by the caller and SHALL be nullable, because an upstream
system is not obliged to send it.

The two SHALL NOT be conflated into one field. They answer different questions — one is
when the customer committed, the other is when we became able to act — and in third-party
logistics they routinely differ: upstream systems send in batches, retry after failures, and
re-run jobs, so an order can arrive minutes or a day after it was placed. With one field,
delay upstream and delay here cannot be told apart.

**The received instant SHALL be the ordering key wherever orders are sequenced**, including
the backorder queue and the recent-orders listing. The placed instant SHALL NOT be used for
sequencing: it is nullable, and it is decided by a system whose clock and delivery schedule
we do not control, so a late-arriving order could otherwise be sequenced ahead of orders
that have been waiting.

A placed instant later than the received instant SHALL be rejected only when it exceeds a
configurable tolerance. Upstream clocks drift by seconds, and comparing strictly would
reject ordinary orders; a placed instant hours or days into the future is a data error and
SHALL be refused. No lower bound SHALL be imposed — an old placed instant is a legitimate
historical import.

#### Scenario: An order without an upstream placed instant is accepted

- **WHEN** an order is placed without an upstream placed instant
- **THEN** the order is persisted, its received instant is set by this system, and its
  placed instant is empty

#### Scenario: An upstream placed instant is preserved as given

- **WHEN** an order is placed carrying an upstream placed instant earlier than now
- **THEN** that instant is persisted unchanged, and the received instant separately records
  when this system accepted the order

#### Scenario: A placed instant beyond the tolerance is rejected

- **WHEN** an order is placed whose upstream placed instant exceeds the received instant by
  more than the configured tolerance
- **THEN** the order is rejected and no order and no line are persisted

#### Scenario: A placed instant within the tolerance is accepted

- **WHEN** an order is placed whose upstream placed instant is slightly later than the
  received instant but within the configured tolerance
- **THEN** the order is accepted, because upstream clocks drift and a few seconds ahead is
  not a data error

##### Example: what the tolerance separates

| Upstream placed instant, relative to received | Result |
| --- | --- |
| three months earlier | accepted — historical import |
| one minute earlier | accepted |
| two seconds later | accepted — clock drift |
| one day later | rejected — data error |
| not supplied | accepted, stored empty |
