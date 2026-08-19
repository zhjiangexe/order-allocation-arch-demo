package com.flowzati.archone.catalog.application.usecase;

import com.flowzati.archone.catalog.domain.aggregate.Owner;
import com.flowzati.archone.catalog.domain.repository.OwnerRepository;
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
