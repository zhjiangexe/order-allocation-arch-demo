package com.flowzati.archone.contracts.inventory.v1;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.flowzati.archone.messaging.events.IntegrationEvent;
import java.util.UUID;

/** Physical stock became available and waiting stock movements may be retried. */
public final class StockAvailabilityIncreasedIntegrationEvent extends IntegrationEvent {

  /** Kept equal to the existing wire value so this change is backward compatible. */
  public static final String EVENT_TYPE = "StockAvailabilityIncreasedIntegrationEvent";

  private final UUID ownerId;
  private final UUID facilityId;
  private final UUID locationId;
  private final String sku;
  private final int quantity;

  @JsonCreator
  public StockAvailabilityIncreasedIntegrationEvent(
      @JsonProperty("eventId") UUID eventId,
      @JsonProperty("ownerId") UUID ownerId,
      @JsonProperty("facilityId") UUID facilityId,
      @JsonProperty("locationId") UUID locationId,
      @JsonProperty("sku") String sku,
      @JsonProperty("quantity") int quantity
  ) {
    super(eventId);
    if (ownerId == null || facilityId == null || locationId == null) {
      throw new IllegalArgumentException("Owner, facility and location IDs are required");
    }
    if (sku == null || sku.isBlank()) {
      throw new IllegalArgumentException("SKU is required");
    }
    if (quantity <= 0) {
      throw new IllegalArgumentException("Availability increase must be positive");
    }
    this.ownerId = ownerId;
    this.facilityId = facilityId;
    this.locationId = locationId;
    this.sku = sku;
    this.quantity = quantity;
  }

  public UUID getOwnerId() {
    return ownerId;
  }

  public UUID getFacilityId() {
    return facilityId;
  }

  public UUID getLocationId() {
    return locationId;
  }

  public String getSku() {
    return sku;
  }

  public int getQuantity() {
    return quantity;
  }

  @Override
  public String eventType() {
    return EVENT_TYPE;
  }
}
