package com.flowzati.archone.common;

import com.github.f4b6a3.uuid.UuidCreator;
import java.util.UUID;

/**
 * ID 生成器，提供符合領域需求的唯一標識符。
 * 放置於 domain.common 是因為領域模型需要用它來創建新的 Entity，
 * 而 Infrastructure 層在持久化時也可以共享此邏輯。
 */
public class IdGenerator {

    /**
     * 生成時間有序的 UUID (v7)，有利於數據庫索引性能。
     */
    public static UUID nextId() {
        return UuidCreator.getTimeOrderedEpoch();
    }
}
