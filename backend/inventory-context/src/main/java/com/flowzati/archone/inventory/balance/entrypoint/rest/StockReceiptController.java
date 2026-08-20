package com.flowzati.archone.inventory.balance.entrypoint.rest;

import com.flowzati.archone.inventory.balance.application.command.ConfirmStockReceiptCommand;
import com.flowzati.archone.inventory.balance.application.receipt.StockReceiptApplicationFacade;
import com.flowzati.archone.inventory.balance.application.receipt.StockReceiptRequest;
import com.flowzati.archone.inventory.balance.application.receipt.StockReceiptRequestConflictException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;
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
 * <p>HTTP 成功表示 inbound picking、move、move line、StockQuant 與 availability Outbox 已在
 * 同一交易提交；等待中的 StockMove 由 availability event 快速觸發，Scheduler 定期補漏。
 */
@RestController
@RequestMapping("/stock-receipts")
public class StockReceiptController {

    private final StockReceiptApplicationFacade stockReceiptApplicationFacade;

    public StockReceiptController(StockReceiptApplicationFacade stockReceiptApplicationFacade) {
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

    /** receiptId 是呼叫方產生的冪等鍵；HTTP retry 必須重用同一個值。 */
    public record ConfirmStockReceiptRequest(
            @NotNull UUID receiptId,

            @NotNull UUID ownerId,

            @NotNull UUID facilityId,

            @NotNull UUID locationId,

            @NotNull String sku,
            LocalDate inDate,
            LocalDate expiryDate,

            @NotNull Integer quantity) {}

    public record StockReceiptConfirmedResponse(UUID receiptId, String sku, int quantity) {}
}
