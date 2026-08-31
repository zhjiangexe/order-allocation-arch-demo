package com.flowzati.archone.logisticsdata.infrastructure.persistence.jpa.mapper;

import com.flowzati.archone.logisticsdata.domain.aggregate.Facility;
import com.flowzati.archone.logisticsdata.infrastructure.persistence.jpa.model.FacilityEntity;

public final class FacilityMapper {

    private FacilityMapper() {}

    public static FacilityEntity toEntity(Facility facility) {
        return new FacilityEntity(facility.getId(), facility.getCode(), facility.getName());
    }

    public static Facility toDomain(FacilityEntity entity) {
        return new Facility(entity.getId(), entity.getCode(), entity.getName());
    }
}
