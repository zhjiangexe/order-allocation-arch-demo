package com.flowzati.archone.catalog.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Owner")
class OwnerTest {

  private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Test
  @DisplayName("應保留代號、名稱、狀態與拆單許可")
  void retainsIdentityStatusAndSplitShipmentPermission() {
    Owner owner = new Owner(OWNER_ID, "OWNER-A", "甲貨主");

    assertThat(owner.getId()).isEqualTo(OWNER_ID);
    assertThat(owner.getCode()).isEqualTo("OWNER-A");
    assertThat(owner.getName()).isEqualTo("甲貨主");
  }

  @Test
  @DisplayName("名稱應獨立存在，不由代號推導——畫面與 log 需要它")
  void keepsNameSeparateFromCode() {
    Owner owner = new Owner(OWNER_ID, "OWNER-A", "甲貨主");

    assertThat(owner.getName()).isNotEqualTo(owner.getCode());
  }

  @Test
  @DisplayName("識別碼為必填")
  void rejectsMissingId() {
    assertThatThrownBy(() -> new Owner(null, "OWNER-A", "甲貨主"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Owner ID");
  }

  @ParameterizedTest(name = "[{index}] code={0}")
  @NullAndEmptySource
  @ValueSource(strings = {"  "})
  @DisplayName("代號為必填")
  void rejectsBlankCode(String code) {
    assertThatThrownBy(() -> new Owner(OWNER_ID, code, "甲貨主"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Owner code");
  }

  @ParameterizedTest(name = "[{index}] name={0}")
  @NullAndEmptySource
  @ValueSource(strings = {"  "})
  @DisplayName("名稱為必填")
  void rejectsBlankName(String name) {
    assertThatThrownBy(() -> new Owner(OWNER_ID, "OWNER-A", name))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Owner name");
  }
}
