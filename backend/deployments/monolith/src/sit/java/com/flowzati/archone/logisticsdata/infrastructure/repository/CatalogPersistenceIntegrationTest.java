package com.flowzati.archone.logisticsdata.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.logisticsdata.domain.aggregate.Owner;
import com.flowzati.archone.logisticsdata.domain.aggregate.Product;
import com.flowzati.archone.logisticsdata.domain.aggregate.Sku;
import com.flowzati.archone.logisticsdata.domain.type.TemperatureZone;
import com.flowzati.archone.logisticsdata.infrastructure.repository.jpa.JpaOwnerRepository;
import com.flowzati.archone.logisticsdata.infrastructure.repository.jpa.JpaSkuRepository;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest(properties = "spring.data.jpa.repositories.enabled=false", showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
@Import({
    PostgreSQLTestConfiguration.class,
    OwnerRepositoryImpl.class,
    ProductRepositoryImpl.class,
    SkuRepositoryImpl.class,
    CatalogPersistenceIntegrationTest.RepositoryConfiguration.class
})
@DisplayName("Catalog PostgreSQL persistence adapter")
class CatalogPersistenceIntegrationTest {

    private static final UUID OWNER_A = uuid(1);
    private static final UUID OWNER_B = uuid(2);

    @Autowired
    private OwnerRepositoryImpl ownerRepository;

    @Autowired
    private ProductRepositoryImpl productRepository;

    @Autowired
    private SkuRepositoryImpl skuRepository;

    @Autowired
    private JpaSkuRepository jpaSkuRepository;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void seedOwners() {
        ownerRepository.save(new Owner(OWNER_A, "OWNER-A", "甲貨主"));
        ownerRepository.save(new Owner(OWNER_B, "OWNER-B", "乙貨主"));
        entityManager.flush();
    }

    @Test
    @DisplayName("應寫入並還原貨主的完整狀態")
    void persistsAndRestoresOwner() {
        entityManager.clear();

        Owner restored = ownerRepository.findById(OWNER_B).orElseThrow();

        assertThat(restored.getCode()).isEqualTo("OWNER-B");
        assertThat(restored.getName()).isEqualTo("乙貨主");
    }

    @Test
    @DisplayName("應以代號穩定排序列出所有貨主")
    void listsOwnersInStableOrder() {
        entityManager.clear();

        assertThat(ownerRepository.findAll()).extracting(Owner::getCode).containsExactly("OWNER-A", "OWNER-B");
    }

    @Test
    @DisplayName("兩個貨主應可各自定義同一個 sku_code，且互不覆蓋、互不可見")
    void keepsCollidingSkuCodesApartPerOwner() {
        productRepository.save(new Product(UUID.randomUUID(), OWNER_A, "P-1", "烏龍茶", TemperatureZone.AMBIENT));
        productRepository.save(new Product(UUID.randomUUID(), OWNER_B, "P-1", "冷凍水餃", TemperatureZone.FROZEN));
        skuRepository.save(new Sku(UUID.randomUUID(), OWNER_A, "SKU-A", "P-1", "500ml", 520));
        skuRepository.save(new Sku(UUID.randomUUID(), OWNER_B, "SKU-A", "P-1", "1kg", 1000));
        entityManager.flush();
        entityManager.clear();

        List<Sku> ownerASkus = skuRepository.findByProduct(OWNER_A, "P-1");
        List<Sku> ownerBSkus = skuRepository.findByProduct(OWNER_B, "P-1");

        assertThat(ownerASkus).singleElement().satisfies(sku -> {
            assertThat(sku.getSkuCode()).isEqualTo("SKU-A");
            assertThat(sku.getSpecName()).isEqualTo("500ml");
            assertThat(sku.getWeightGram()).isEqualTo(520);
        });
        assertThat(ownerBSkus).singleElement().satisfies(sku -> {
            assertThat(sku.getSkuCode()).isEqualTo("SKU-A");
            assertThat(sku.getSpecName()).isEqualTo("1kg");
            assertThat(sku.getWeightGram()).isEqualTo(1000);
        });
    }

    @Test
    @DisplayName("應只列出指定貨主的款，並以款號穩定排序")
    void listsProductsOfOneOwnerOnly() {
        productRepository.save(new Product(UUID.randomUUID(), OWNER_A, "P-2", "紅茶", TemperatureZone.AMBIENT));
        productRepository.save(new Product(UUID.randomUUID(), OWNER_A, "P-1", "烏龍茶", TemperatureZone.AMBIENT));
        productRepository.save(new Product(UUID.randomUUID(), OWNER_B, "P-3", "冷凍水餃", TemperatureZone.FROZEN));
        entityManager.flush();
        entityManager.clear();

        assertThat(productRepository.findByOwner(OWNER_A))
                .extracting(Product::getProductCode)
                .containsExactly("P-1", "P-2");
    }

    @Test
    @DisplayName("應只列出指定款的規格，並以規格編碼穩定排序")
    void listsSkusOfOneProductOnly() {
        productRepository.save(new Product(UUID.randomUUID(), OWNER_A, "P-1", "冷凍水餃", TemperatureZone.FROZEN));
        productRepository.save(new Product(UUID.randomUUID(), OWNER_A, "P-2", "烏龍茶", TemperatureZone.AMBIENT));
        skuRepository.save(new Sku(UUID.randomUUID(), OWNER_A, "SKU-B", "P-1", "1kg", 1000));
        skuRepository.save(new Sku(UUID.randomUUID(), OWNER_A, "SKU-A", "P-1", "500g", 500));
        skuRepository.save(new Sku(UUID.randomUUID(), OWNER_A, "SKU-C", "P-2", "500ml", 520));
        entityManager.flush();
        entityManager.clear();

        assertThat(skuRepository.findByProduct(OWNER_A, "P-1"))
                .extracting(Sku::getSkuCode)
                .containsExactly("SKU-A", "SKU-B");
    }

    @Test
    @DisplayName("規格應無法指向他貨主的款——複合外鍵擋住跨貨主的參照")
    void rejectsSkuPointingAtAnotherOwnersProduct() {
        productRepository.save(new Product(UUID.randomUUID(), OWNER_A, "P-1", "烏龍茶", TemperatureZone.AMBIENT));
        entityManager.flush();

        // 只有 OWNER_A 有 P-1；OWNER_B 的規格指向同一個款號應被複合外鍵擋下。
        skuRepository.save(new Sku(UUID.randomUUID(), OWNER_B, "SKU-A", "P-1", "500ml", 520));

        // 經 JPA repository 而非 EntityManager 觸發 flush：後者不走 Spring 的例外轉譯，
        // 拋出的會是 PersistenceException 而非 DataIntegrityViolationException。
        assertThatThrownBy(jpaSkuRepository::flush).isInstanceOf(DataIntegrityViolationException.class);
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = JpaOwnerRepository.class)
    static class RepositoryConfiguration {}
}
