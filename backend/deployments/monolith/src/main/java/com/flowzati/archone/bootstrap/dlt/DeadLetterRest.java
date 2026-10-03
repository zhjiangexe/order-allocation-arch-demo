package com.flowzati.archone.bootstrap.dlt;

import com.flowzati.archone.bootstrap.dlt.DeadLetterQueryService.DeadLetterDetail;
import com.flowzati.archone.bootstrap.dlt.DeadLetterQueryService.DeadLetterPage;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Demo-only DLT inspection; no publish or replay operations. */
@RestController
@Profile("dev")
@RequestMapping("/demo/dead-letters")
public class DeadLetterRest {

    private final DeadLetterQueryService query;

    public DeadLetterRest(DeadLetterQueryService query) {
        this.query = query;
    }

    @GetMapping
    public DeadLetterPage list(
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) String consumerGroup,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int limit) {
        return query.list(topic, consumerGroup, page, limit);
    }

    @GetMapping("/{topic}/{partition}/{offset}")
    public DeadLetterDetail detail(@PathVariable String topic, @PathVariable int partition, @PathVariable long offset) {
        return query.detail(topic, partition, offset);
    }
}
