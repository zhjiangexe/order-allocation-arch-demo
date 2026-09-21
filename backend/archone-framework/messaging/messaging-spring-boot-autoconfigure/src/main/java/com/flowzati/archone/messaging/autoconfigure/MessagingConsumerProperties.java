package com.flowzati.archone.messaging.autoconfigure;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Stable subscriber-to-broker-group mappings; unmapped subscribers use identity mapping. */
@ConfigurationProperties("archone.messaging.consumer")
public class MessagingConsumerProperties {

    private Map<String, String> groups = new LinkedHashMap<>();

    public Map<String, String> getGroups() {
        return groups;
    }

    public void setGroups(Map<String, String> groups) {
        this.groups = groups == null ? new LinkedHashMap<>() : new LinkedHashMap<>(groups);
    }
}
