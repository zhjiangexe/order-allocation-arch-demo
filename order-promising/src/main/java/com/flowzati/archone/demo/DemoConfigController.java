package com.flowzati.archone.demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 揭露目前生效的分區策略，讓操作台能顯示現在跑的是 v1（orderId 分區）還是 v3（sku 分區
 * single-writer）。
 *
 * <p>只揭露、不提供切換：策略在啟動時由 {@code @Value} 解析，執行期改不了。提供一個做不到
 * 的切換按鈕比不提供更糟。
 */
@RestController
@RequestMapping("/demo")
@Profile("dev")
public class DemoConfigController {

    private final String partitionKeyStrategy;

    public DemoConfigController(
            @Value("${archone.allocation.partition-key-strategy:order-id}") String partitionKeyStrategy) {
        this.partitionKeyStrategy = partitionKeyStrategy;
    }

    @GetMapping("/config")
    public DemoConfigResponse getConfig() {
        return new DemoConfigResponse(partitionKeyStrategy);
    }

    public record DemoConfigResponse(String partitionKeyStrategy) {}
}
