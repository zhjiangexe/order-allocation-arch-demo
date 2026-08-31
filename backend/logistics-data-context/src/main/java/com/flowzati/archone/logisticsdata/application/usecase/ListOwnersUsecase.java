package com.flowzati.archone.logisticsdata.application.usecase;

import com.flowzati.archone.logisticsdata.application.store.OwnerStore;
import com.flowzati.archone.logisticsdata.domain.aggregate.Owner;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ListOwnersUsecase {

    private final OwnerStore ownerStore;

    public ListOwnersUsecase(OwnerStore ownerStore) {
        this.ownerStore = ownerStore;
    }

    public List<Owner> listAll() {
        return ownerStore.findAll();
    }
}
