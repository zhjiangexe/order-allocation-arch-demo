package com.flowzati.archone.logisticsdata.application.usecase;

import com.flowzati.archone.logisticsdata.application.store.OwnerRepository;
import com.flowzati.archone.logisticsdata.domain.aggregate.Owner;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ListOwnersUsecase {

    private final OwnerRepository ownerRepository;

    public ListOwnersUsecase(OwnerRepository ownerRepository) {
        this.ownerRepository = ownerRepository;
    }

    public List<Owner> listAll() {
        return ownerRepository.findAll();
    }
}
