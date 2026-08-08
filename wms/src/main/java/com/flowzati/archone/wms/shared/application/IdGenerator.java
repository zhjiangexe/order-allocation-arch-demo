package com.flowzati.archone.wms.shared.application;

import java.util.UUID;

/** 由 bootstrap 注入 UUID v7 或其他識別碼策略，domain core 不綁定產生方式。 */
@FunctionalInterface
public interface IdGenerator {

  UUID nextId();
}
