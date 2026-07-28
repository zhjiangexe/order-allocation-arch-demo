package com.flowzati.archone.catalog.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.flowzati.archone.catalog.domain.model.Owner;
import com.flowzati.archone.catalog.domain.model.Product;
import com.flowzati.archone.catalog.domain.model.Sku;
import com.flowzati.archone.catalog.domain.model.TemperatureZone;
import com.flowzati.archone.catalog.domain.repository.OwnerRepository;
import com.flowzati.archone.catalog.domain.repository.ProductRepository;
import com.flowzati.archone.catalog.domain.repository.SkuRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Catalog query usecases")
class CatalogQueryUsecaseTest {

  private static final UUID OWNER_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID OWNER_B = UUID.fromString("00000000-0000-0000-0000-000000000002");

  @Mock
  private OwnerRepository ownerRepository;

  @Mock
  private ProductRepository productRepository;

  @Mock
  private SkuRepository skuRepository;

  @Nested
  @DisplayName("ListOwnersUsecase")
  class ListOwners {

    @Test
    @DisplayName("應原樣回傳 repository 的貨主清單與其排序")
    void returnsOwnersInRepositoryOrder() {
      Owner first = new Owner(OWNER_A, "OWNER-A", "甲貨主");
      Owner second = new Owner(OWNER_B, "OWNER-B", "乙貨主");
      when(ownerRepository.findAll()).thenReturn(List.of(first, second));

      List<Owner> owners = new ListOwnersUsecase(ownerRepository).listAll();

      assertThat(owners).extracting(Owner::getCode).containsExactly("OWNER-A", "OWNER-B");
    }
  }

  @Nested
  @DisplayName("ListProductsUsecase")
  class ListProducts {

    @Test
    @DisplayName("應以呼叫端指定的貨主查詢，不會漏掉貨主而查到全部")
    void queriesWithTheGivenOwner() {
      Product product = new Product(
          UUID.randomUUID(), OWNER_B, "P-1", "冷凍水餃", TemperatureZone.FROZEN);
      when(productRepository.findByOwner(OWNER_B)).thenReturn(List.of(product));

      List<Product> products = new ListProductsUsecase(productRepository).listByOwner(OWNER_B);

      assertThat(products).singleElement()
          .satisfies(found -> assertThat(found.getOwnerId()).isEqualTo(OWNER_B));
    }
  }

  @Nested
  @DisplayName("ListSkusUsecase")
  class ListSkus {

    @Test
    @DisplayName("應以貨主與款號兩者查詢，且兩個參數不得互換")
    void queriesWithBothOwnerAndProductCode() {
      Sku sku = new Sku(UUID.randomUUID(), OWNER_A, "SKU-A", "P-1", "500ml", 520);
      // 只在收到 (OWNER_A, "P-1") 這個順序時才回傳；參數互換會得到空清單。
      when(skuRepository.findByProduct(OWNER_A, "P-1")).thenReturn(List.of(sku));

      List<Sku> skus = new ListSkusUsecase(skuRepository).listByProduct(OWNER_A, "P-1");

      assertThat(skus).singleElement()
          .satisfies(found -> {
            assertThat(found.getOwnerId()).isEqualTo(OWNER_A);
            assertThat(found.getProductCode()).isEqualTo("P-1");
          });
    }
  }
}
