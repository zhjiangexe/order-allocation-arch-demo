package com.flowzati.archone.catalog.entrypoint.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.flowzati.archone.catalog.application.usecase.ListNodesForOwnerUsecase;
import com.flowzati.archone.catalog.application.usecase.ListOwnersUsecase;
import com.flowzati.archone.catalog.application.usecase.ListProductsUsecase;
import com.flowzati.archone.catalog.application.usecase.ListSkusUsecase;
import com.flowzati.archone.catalog.domain.model.FulfillmentNode;
import com.flowzati.archone.catalog.domain.model.Owner;
import com.flowzati.archone.catalog.domain.model.Product;
import com.flowzati.archone.catalog.domain.model.Sku;
import com.flowzati.archone.catalog.domain.model.TemperatureZone;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResultAssert;

@WebMvcTest(CatalogController.class)
@DisplayName("Catalog HTTP surface")
class CatalogControllerTest {

  private static final UUID OWNER_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID OWNER_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID NODE_NORTH = UUID.fromString("00000000-0000-0000-0000-000000000011");
  private static final UUID NODE_CENTRAL = UUID.fromString("00000000-0000-0000-0000-000000000012");

  @Autowired
  private MockMvcTester mvc;

  @MockitoBean
  private ListOwnersUsecase listOwnersUsecase;

  @MockitoBean
  private ListProductsUsecase listProductsUsecase;

  @MockitoBean
  private ListSkusUsecase listSkusUsecase;

  @MockitoBean
  private ListNodesForOwnerUsecase listNodesForOwnerUsecase;

  @Test
  @DisplayName("應只列出該貨主已指派的倉庫")
  void listsOnlyTheOwnersAssignedNodes() {
    when(listNodesForOwnerUsecase.listByOwner(OWNER_A)).thenReturn(List.of(
        new FulfillmentNode(NODE_NORTH, "WH-NORTH", "北部倉"),
        new FulfillmentNode(NODE_CENTRAL, "WH-CENTRAL", "中部倉")));

    MvcTestResultAssert response = assertThat(mvc.get().uri("/owners/{id}/nodes", OWNER_A));

    response.hasStatusOk();
    response.bodyJson().extractingPath("$.length()").isEqualTo(2);
    response.bodyJson().extractingPath("$[0].nodeId").isEqualTo(NODE_NORTH.toString());
    response.bodyJson().extractingPath("$[0].code").isEqualTo("WH-NORTH");
    response.bodyJson().extractingPath("$[0].name").isEqualTo("北部倉");
    // 契約不帶狀態、能力、產能——系統不做選倉決策，那些欄位不存在
    response.bodyJson().extractingPath("$[0]").asMap()
        .containsOnlyKeys("nodeId", "code", "name");
  }

  @Test
  @DisplayName("未知貨主應回空陣列而非 404——與款、規格的處理一致")
  void returnsEmptyListForUnknownOwner() {
    when(listNodesForOwnerUsecase.listByOwner(OWNER_B)).thenReturn(List.of());

    MvcTestResultAssert response = assertThat(mvc.get().uri("/owners/{id}/nodes", OWNER_B));

    response.hasStatusOk();
    response.bodyJson().extractingPath("$.length()").isEqualTo(0);
  }

  @Test
  @DisplayName("應列出所有貨主，含名稱與狀態")
  void listsOwners() {
    when(listOwnersUsecase.listAll()).thenReturn(List.of(
        new Owner(OWNER_A, "OWNER-A", "甲貨主"),
        new Owner(OWNER_B, "OWNER-B", "乙貨主")));

    MvcTestResultAssert response = assertThat(mvc.get().uri("/owners"));

    response.hasStatus(200);
    response.bodyJson().extractingPath("$.length()").isEqualTo(2);
    response.bodyJson().extractingPath("$[0].code").isEqualTo("OWNER-A");
    response.bodyJson().extractingPath("$[0].name").isEqualTo("甲貨主");
  }

  @Test
  @DisplayName("應在貨主路徑之下列出款，並回報溫層")
  void listsProductsWithinTheirOwner() {
    when(listProductsUsecase.listByOwner(OWNER_A)).thenReturn(List.of(
        new Product(UUID.randomUUID(), OWNER_A, "P-1", "冷凍水餃", TemperatureZone.FROZEN)));

    MvcTestResultAssert response =
        assertThat(mvc.get().uri("/owners/{ownerId}/products", OWNER_A));

    response.hasStatus(200);
    response.bodyJson().extractingPath("$[0].productCode").isEqualTo("P-1");
    response.bodyJson().extractingPath("$[0].name").isEqualTo("冷凍水餃");
    response.bodyJson().extractingPath("$[0].temperatureZone").isEqualTo("FROZEN");
    response.bodyJson().extractingPath("$[0].ownerId").isEqualTo(OWNER_A.toString());
  }

  @Test
  @DisplayName("應在款的路徑之下列出規格，並回報規格名與重量")
  void listsSkusWithinTheirProduct() {
    when(listSkusUsecase.listByProduct(OWNER_A, "P-1")).thenReturn(List.of(
        new Sku(UUID.randomUUID(), OWNER_A, "SKU-A", "P-1", "500g", 500),
        new Sku(UUID.randomUUID(), OWNER_A, "SKU-B", "P-1", "1kg", 1000)));

    MvcTestResultAssert response = assertThat(
        mvc.get().uri("/owners/{ownerId}/products/{productCode}/skus", OWNER_A, "P-1"));

    response.hasStatus(200);
    response.bodyJson().extractingPath("$.length()").isEqualTo(2);
    response.bodyJson().extractingPath("$[0].skuCode").isEqualTo("SKU-A");
    response.bodyJson().extractingPath("$[0].specName").isEqualTo("500g");
    response.bodyJson().extractingPath("$[0].weightGram").isEqualTo(500);
    response.bodyJson().extractingPath("$[1].weightGram").isEqualTo(1000);
  }

  @Test
  @DisplayName("同一個款號在他貨主之下應是另一組規格——路徑裡的貨主決定查到什麼")
  void scopesProductCodeToTheOwnerInThePath() {
    when(listSkusUsecase.listByProduct(OWNER_A, "P-1")).thenReturn(List.of(
        new Sku(UUID.randomUUID(), OWNER_A, "SKU-A", "P-1", "500ml", 520)));
    when(listSkusUsecase.listByProduct(OWNER_B, "P-1")).thenReturn(List.of(
        new Sku(UUID.randomUUID(), OWNER_B, "SKU-A", "P-1", "1kg", 1000)));

    assertThat(mvc.get().uri("/owners/{ownerId}/products/{productCode}/skus", OWNER_A, "P-1"))
        .bodyJson().extractingPath("$[0].weightGram").isEqualTo(520);
    assertThat(mvc.get().uri("/owners/{ownerId}/products/{productCode}/skus", OWNER_B, "P-1"))
        .bodyJson().extractingPath("$[0].weightGram").isEqualTo(1000);
  }

  @Test
  @DisplayName("查無資料時應回空陣列而非 404——「這個貨主沒有款」與「貨主不存在」在此不區分")
  void returnsAnEmptyArrayWhenNothingMatches() {
    when(listProductsUsecase.listByOwner(OWNER_B)).thenReturn(List.of());

    MvcTestResultAssert response =
        assertThat(mvc.get().uri("/owners/{ownerId}/products", OWNER_B));

    response.hasStatus(200);
    response.bodyJson().extractingPath("$.length()").isEqualTo(0);
  }

  @Test
  @DisplayName("主檔端點應只接受讀取——寫入方法不得註冊")
  void rejectsWrites() {
    assertThat(mvc.post().uri("/owners")).hasStatus(405);
    assertThat(mvc.put().uri("/owners/{ownerId}/products", OWNER_A)).hasStatus(405);
    assertThat(mvc.delete().uri("/owners/{ownerId}/products/{productCode}/skus", OWNER_A, "P-1"))
        .hasStatus(405);
  }
}
