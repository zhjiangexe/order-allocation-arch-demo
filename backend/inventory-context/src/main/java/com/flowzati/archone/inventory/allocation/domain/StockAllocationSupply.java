package com.flowzati.archone.inventory.allocation.domain;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable owner/location-scoped FEFO supply offered to one stock allocation planning attempt. */
public final class StockAllocationSupply {

    private final UUID ownerId;
    private final UUID locationId;
    private final Map<String, List<StockQuantSupply>> suppliesBySku;

    private StockAllocationSupply(
            UUID ownerId, UUID locationId, Map<String, ? extends Collection<StockQuantSupply>> suppliesBySku) {
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId must not be null");
        this.locationId = Objects.requireNonNull(locationId, "locationId must not be null");
        Objects.requireNonNull(suppliesBySku, "suppliesBySku must not be null");

        Map<String, List<StockQuantSupply>> copied = new LinkedHashMap<>();
        suppliesBySku.forEach((skuCode, supplies) -> {
            if (skuCode == null || skuCode.isBlank()) {
                throw new IllegalArgumentException("SKU code must not be blank");
            }
            Objects.requireNonNull(supplies, "Supplies for " + skuCode + " must not be null");
            List<StockQuantSupply> group = List.copyOf(supplies);
            group.forEach(supply -> requireSupplyInScope(skuCode, supply));
            copied.put(skuCode, group);
        });
        this.suppliesBySku = Collections.unmodifiableMap(copied);
    }

    public static StockAllocationSupply of(
            UUID ownerId, UUID locationId, Map<String, ? extends Collection<StockQuantSupply>> suppliesBySku) {
        return new StockAllocationSupply(ownerId, locationId, suppliesBySku);
    }

    public UUID ownerId() {
        return ownerId;
    }

    public UUID locationId() {
        return locationId;
    }

    public Set<String> skuCodes() {
        return suppliesBySku.keySet();
    }

    /** Returns one demanded SKU group; a missing group is malformed input, while an empty group is valid shortage. */
    public List<StockQuantSupply> forSku(String skuCode) {
        List<StockQuantSupply> supplies = suppliesBySku.get(skuCode);
        if (supplies == null) {
            throw new IllegalArgumentException("Supply is missing a group for SKU " + skuCode);
        }
        return supplies;
    }

    public int availableToPromiseFor(String skuCode) {
        return forSku(skuCode).stream()
                .mapToInt(StockQuantSupply::availableToPromise)
                .sum();
    }

    public boolean isEmpty() {
        return suppliesBySku.values().stream().allMatch(List::isEmpty);
    }

    /** Verifies that this projection completely covers the demand's owner, location and requested SKU groups. */
    public void requireCovers(UUID requiredOwnerId, UUID requiredLocationId, Collection<String> requiredSkuCodes) {
        Objects.requireNonNull(requiredOwnerId, "requiredOwnerId must not be null");
        Objects.requireNonNull(requiredLocationId, "requiredLocationId must not be null");
        Objects.requireNonNull(requiredSkuCodes, "requiredSkuCodes must not be null");
        if (!ownerId.equals(requiredOwnerId)) {
            throw new IllegalArgumentException("Demand and supply must belong to the same owner");
        }
        if (!locationId.equals(requiredLocationId)) {
            throw new IllegalArgumentException("Demand and supply must belong to the same location");
        }
        if (!suppliesBySku.keySet().containsAll(requiredSkuCodes)) {
            Set<String> missing = new LinkedHashSet<>(requiredSkuCodes);
            missing.removeAll(suppliesBySku.keySet());
            throw new IllegalArgumentException("Supply is missing groups for demanded SKUs " + missing);
        }
    }

    private void requireSupplyInScope(String skuCode, StockQuantSupply supply) {
        Objects.requireNonNull(supply, "Supply grouped under " + skuCode + " must not be null");
        if (!supply.skuCode().equals(skuCode)) {
            throw new IllegalArgumentException("Supply grouped under " + skuCode + " belongs to " + supply.skuCode());
        }
        if (!supply.ownerId().equals(ownerId)) {
            throw new IllegalArgumentException("Demand and supply must belong to the same owner");
        }
        if (!supply.locationId().equals(locationId)) {
            throw new IllegalArgumentException("Demand and supply must belong to the same location");
        }
    }
}
