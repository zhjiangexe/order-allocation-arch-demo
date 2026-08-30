package com.flowzati.archone.logisticsdata.application.store;

import com.flowzati.archone.logisticsdata.domain.aggregate.Owner;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OwnerRepository {

    void save(Owner owner);

    Optional<Owner> findById(UUID ownerId);

    /** 以代號遞增排序，讓下拉選單與列表的順序在重複查詢之間保持一致。 */
    List<Owner> findAll();
}
