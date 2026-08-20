package com.flowzati.archone.support.spring.web.validation;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 控制 validation message catalog 的來源與重新載入週期。 */
@ConfigurationProperties("archone.web.validation")
public record SpringWebValidationProperties(List<String> messageBasenames, Duration messageCacheDuration) {

    private static final List<String> DEFAULT_MESSAGE_BASENAMES =
            List.of("classpath:i18n/validation/constraints_template", "classpath:i18n/validation/problem_detail");
    private static final Duration DEFAULT_MESSAGE_CACHE_DURATION = Duration.ofMinutes(5);

    public SpringWebValidationProperties {
        messageBasenames = messageBasenames == null || messageBasenames.isEmpty()
                ? DEFAULT_MESSAGE_BASENAMES
                : List.copyOf(messageBasenames);
        messageCacheDuration = messageCacheDuration == null ? DEFAULT_MESSAGE_CACHE_DURATION : messageCacheDuration;
    }
}
