package com.flowzati.archone.inventory.allocation.entrypoint.rest;

import com.flowzati.archone.inventory.allocation.application.query.AllocationDemandQueryService;
import com.flowzati.archone.inventory.allocation.application.query.AllocationDemandView;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Allocation demand 的唯讀操作台入口；配置 command 仍由事件或 Temporal 驅動。 */
@RestController
@RequestMapping("/allocation-demands")
public class AllocationDemandController {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 200;

    private final AllocationDemandQueryService queryService;

    public AllocationDemandController(AllocationDemandQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public List<AllocationDemandView> list(
            @RequestParam(name = "status", defaultValue = "PENDING") String status,
            @RequestParam(name = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit) {
        if (!"PENDING".equalsIgnoreCase(status)) {
            throw new IllegalArgumentException("Only PENDING allocation demands can be listed");
        }
        if (limit <= 0 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        return queryService.listPending(limit);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleInvalidRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(exception.getMessage());
    }
}
