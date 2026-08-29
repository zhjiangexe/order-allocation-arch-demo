package com.flowzati.archone.inventory.location.application;

import java.util.UUID;

/** Immutable location projection used by facility-location queries. */
public record StockLocationView(UUID locationId, UUID facilityId, String code, String name) {}
