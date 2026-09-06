package com.flowzati.archone.inventory.balance.entrypoint.rest;

import com.flowzati.archone.inventory.balance.application.StockReceiptRequest;
import com.flowzati.archone.inventory.balance.application.exception.StockReceiptRequestConflictException;
import com.flowzati.archone.inventory.balance.application.invocation.ConfirmStockReceiptCommand;
import com.flowzati.archone.inventory.balance.application.service.StockReceiptApplicationFacade;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stock context 的同步收貨入口。
 *
 * <p>HTTP 成功表示 inbound operation、move、move line、StockQuant 與 availability Outbox 已在
 * 同一交易提交；等待中的 StockMove 由 availability event 快速觸發，Scheduler 定期補漏。
 */
@RestController
@RequestMapping("/stock-receipts")
public class StockReceiptRest {

    private final StockReceiptApplicationFacade stockReceiptApplicationFacade;

    public StockReceiptRest(StockReceiptApplicationFacade stockReceiptApplicationFacade) {
        this.stockReceiptApplicationFacade = stockReceiptApplicationFacade;
    }

    @PostMapping
    public StockReceiptConfirmedResponse confirm(@Valid @RequestBody ConfirmStockReceiptRequest request) {
        ConfirmStockReceiptCommand command = new ConfirmStockReceiptCommand(
                request.ownerId(),
                request.facilityId(),
                request.locationId(),
                request.sku(),
                request.inDate(),
                request.expiryDate(),
                request.quantity());
        stockReceiptApplicationFacade.confirm(new StockReceiptRequest(request.receiptId(), command));
        return new StockReceiptConfirmedResponse(request.receiptId(), request.sku(), request.quantity());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleInvalidRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(exception.getMessage());
    }

    @ExceptionHandler(StockReceiptRequestConflictException.class)
    public ResponseEntity<String> handleIdempotencyConflict(StockReceiptRequestConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(exception.getMessage());
    }
}
