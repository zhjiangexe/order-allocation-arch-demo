package com.flowzati.archone.catalog.application.usecase;

import com.flowzati.archone.catalog.domain.model.Product;
import com.flowzati.archone.catalog.domain.repository.ProductRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ListProductsUsecase {

  private final ProductRepository productRepository;

  public ListProductsUsecase(ProductRepository productRepository) {
    this.productRepository = productRepository;
  }

  /** 貨主是必要參數而非選用篩選：款號跨貨主撞號，不帶貨主的清單無法解讀。 */
  public List<Product> listByOwner(UUID ownerId) {
    return productRepository.findByOwner(ownerId);
  }
}
