package com.flowzati.archone.catalog.entrypoint.rest;

import com.flowzati.archone.catalog.application.usecase.ListOwnersUsecase;
import com.flowzati.archone.catalog.application.usecase.ListProductsUsecase;
import com.flowzati.archone.catalog.application.usecase.ListSkusUsecase;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 主檔的唯讀表面，供下單表單逐層選擇：貨主 → 款 → 規格。
 *
 * <p>款與規格的路徑巢狀在貨主之下，而不是 {@code /products?ownerId=…}：在 3PL 裡編碼由
 * 貨主自訂、跨貨主撞號，貨主不是一個可省略的篩選條件，而是這些資源存在的前提。巢狀路徑
 * 讓「沒有貨主就沒有款」這件事在 URL 上就成立。
 *
 * <p>刻意只有查詢。主檔由 seed 建立——維護介面有自己的授權與稽核需求，提供半套版本只會
 * 招來對它的依賴。
 */
@RestController
@RequestMapping("/owners")
public class CatalogController {

  private final ListOwnersUsecase listOwnersUsecase;
  private final ListProductsUsecase listProductsUsecase;
  private final ListSkusUsecase listSkusUsecase;

  public CatalogController(
      ListOwnersUsecase listOwnersUsecase,
      ListProductsUsecase listProductsUsecase,
      ListSkusUsecase listSkusUsecase
  ) {
    this.listOwnersUsecase = listOwnersUsecase;
    this.listProductsUsecase = listProductsUsecase;
    this.listSkusUsecase = listSkusUsecase;
  }

  @GetMapping
  public List<OwnerResponse> listOwners() {
    return listOwnersUsecase.listAll().stream().map(OwnerResponse::from).toList();
  }

  /**
   * 查無資料時回空陣列而非 {@code 404}：這裡不區分「這個貨主沒有款」與「貨主不存在」。
   * 兩者對呼叫端的下一步相同——沒有東西可選——而區分它們要多一次貨主存在性查詢，換到的
   * 只是錯誤訊息的精確度。
   */
  @GetMapping("/{ownerId}/products")
  public List<ProductResponse> listProducts(@PathVariable UUID ownerId) {
    return listProductsUsecase.listByOwner(ownerId).stream().map(ProductResponse::from).toList();
  }

  @GetMapping("/{ownerId}/products/{productCode}/skus")
  public List<SkuResponse> listSkus(
      @PathVariable UUID ownerId,
      @PathVariable String productCode
  ) {
    return listSkusUsecase.listByProduct(ownerId, productCode).stream()
        .map(SkuResponse::from)
        .toList();
  }
}
