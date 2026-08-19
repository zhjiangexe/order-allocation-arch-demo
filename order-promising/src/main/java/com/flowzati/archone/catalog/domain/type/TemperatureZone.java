package com.flowzati.archone.catalog.domain.type;

/**
 * 商品所需的溫層，屬「款」層級。
 *
 * <p>R6 以它作為節點的硬約束：節點的 capabilities 不含此溫層則該節點被排除。
 */
public enum TemperatureZone {
    AMBIENT,
    CHILLED,
    FROZEN
}
