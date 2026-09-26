# Pre-rename baseline

Captured on 2026-08-29 before applying the port renames.

- `port-classification.tsv` records all 22 Inventory custom ports and Spring Data repository interfaces, their target
  classification and every direct Java caller.
- `./gradlew :inventory-context:test` ran 155 tests: 154 passed and one architecture test failed.
- The only failure was `inventoryUsesFiveBusinessModulesBeforeLayer`, caused by the already-obsolete
  `inventory/allocation/application/store/StockAllocationSupplyFinder.java` path.
- Moving that Finder to `allocation/planning/application/port` is explicitly required by design decision 2 and task
  3.2. No behavioral test failed before the rename.
