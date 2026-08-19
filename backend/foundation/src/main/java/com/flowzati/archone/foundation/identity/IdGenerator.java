package com.flowzati.archone.foundation.identity;

import com.github.f4b6a3.uuid.UuidCreator;
import java.util.UUID;

/**
 * ID 生成器，提供符合領域需求的唯一標識符。
 * 這是 framework-neutral 的 UUIDv7 implementation；目前保留 static API 以降低搬移風險，
 * 後續可在各 application port 穩定後改為注入式 generator。
 */
public final class IdGenerator {

    private IdGenerator() {}

    /** 生成時間有序的 UUID (v7)，有利於數據庫索引性能。 */
    public static UUID nextId() {
        return UuidCreator.getTimeOrderedEpoch();
    }
}
