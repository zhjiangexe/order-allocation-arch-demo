package com.flowzati.archone.catalog.infrastructure.mapper;

import com.flowzati.archone.catalog.domain.model.FulfillmentNode;
import com.flowzati.archone.catalog.infrastructure.entity.FulfillmentNodeEntity;

public final class FulfillmentNodeMapper {

  private FulfillmentNodeMapper() {
  }

  public static FulfillmentNodeEntity toEntity(FulfillmentNode node) {
    return new FulfillmentNodeEntity(node.getId(), node.getCode(), node.getName());
  }

  public static FulfillmentNode toDomain(FulfillmentNodeEntity entity) {
    return new FulfillmentNode(entity.getId(), entity.getCode(), entity.getName());
  }
}
