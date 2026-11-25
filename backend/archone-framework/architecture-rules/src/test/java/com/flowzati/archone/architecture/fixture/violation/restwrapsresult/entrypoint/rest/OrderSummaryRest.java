package com.flowzati.archone.architecture.fixture.violation.restwrapsresult.entrypoint.rest;

import com.flowzati.archone.architecture.fixture.violation.restwrapsresult.application.result.OrderSummary;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 包在 ResponseEntity 與 List 裡也一樣不行：內部的 result 不是對外格式。 */
@RestController
public class OrderSummaryRest {
    public ResponseEntity<List<OrderSummary>> list() {
        return ResponseEntity.ok(List.of());
    }
}
