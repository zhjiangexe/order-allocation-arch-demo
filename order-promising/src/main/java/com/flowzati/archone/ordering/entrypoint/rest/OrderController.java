package com.flowzati.archone.ordering.entrypoint.rest;

import com.flowzati.archone.ordering.application.usecase.PlaceOrderUsecase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
public class OrderController {
  private final PlaceOrderUsecase placeOrderUsecase;

  public OrderController(PlaceOrderUsecase placeOrderUsecase) {
    this.placeOrderUsecase = placeOrderUsecase;
  }

  @RequestMapping
  public ResponseEntity<?> placeOrder(@RequestParam String sku, @RequestParam Integer quantity) {
    Long l = placeOrderUsecase.placeOrder(sku, quantity);
    return ResponseEntity.ok(l);
  }
}
