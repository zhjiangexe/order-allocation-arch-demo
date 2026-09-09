package com.flowzati.archone.inventory.movement.entrypoint.rest;

import com.flowzati.archone.inventory.movement.application.service.StockOperationQueryService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only warehouse operation queue; commands remain event- or workflow-driven. */
@RestController
@RequestMapping("/stock-operations")
public class StockOperationRest {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 200;

    private final StockOperationQueryService queryService;

    public StockOperationRest(StockOperationQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public List<StockOperationResponse> list(
            @RequestParam(name = "state", defaultValue = "CONFIRMED") String state,
            @RequestParam(name = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit,
            @RequestParam(name = "ownerId", required = false) UUID ownerId) {
        if (!"CONFIRMED".equalsIgnoreCase(state)) {
            throw new IllegalArgumentException("Only CONFIRMED stock operations can be listed");
        }
        if (limit <= 0 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        return (ownerId == null ? queryService.listConfirmed(limit) : queryService.listConfirmed(ownerId, limit))
                .stream().map(StockOperationResponse::from).toList();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleInvalidRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(exception.getMessage());
    }
}
