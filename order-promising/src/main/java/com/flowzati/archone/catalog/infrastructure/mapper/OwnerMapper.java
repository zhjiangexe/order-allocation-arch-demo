package com.flowzati.archone.catalog.infrastructure.mapper;

import com.flowzati.archone.catalog.domain.model.Owner;
import com.flowzati.archone.catalog.infrastructure.entity.OwnerEntity;

public final class OwnerMapper {

  private OwnerMapper() {
  }

  public static OwnerEntity toEntity(Owner owner) {
    return new OwnerEntity(
        owner.getId(),
        owner.getCode(),
        owner.getName()
    );
  }

  public static Owner toDomain(OwnerEntity entity) {
    return new Owner(
        entity.getId(),
        entity.getCode(),
        entity.getName()
    );
  }
}
