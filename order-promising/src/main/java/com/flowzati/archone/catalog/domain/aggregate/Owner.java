package com.flowzati.archone.catalog.domain.aggregate;

import java.util.UUID;

/**
 * 貨主：委託本倉儲存與出貨的一方。倉庫不擁有貨，貨屬於委託方。
 *
 * <p>本 change 沒有行為方法——主檔由 seed 建立，不提供寫入介面。
 */
public class Owner {

    private final UUID id;
    private final String code;
    private final String name;

    public Owner(UUID id, String code, String name) {
        if (id == null) {
            throw new IllegalArgumentException("Owner ID is required");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Owner code is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Owner name is required");
        }
        this.id = id;
        this.code = code;
        this.name = name;
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }
}
