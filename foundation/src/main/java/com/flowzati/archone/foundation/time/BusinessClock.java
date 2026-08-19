package com.flowzati.archone.foundation.time;

import java.time.Instant;
import java.time.LocalDate;

/** 提供一致的目前瞬間與營運日期，不讓 application use case 依賴 Spring 時鐘實作。 */
public interface BusinessClock {

  LocalDate today();

  Instant instant();
}
