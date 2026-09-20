package com.flowzati.archone.inventory.location.entrypoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.flowzati.archone.inventory.location.application.usecase.ListStockLocationsUsecase;
import com.flowzati.archone.inventory.location.application.view.StockLocationView;
import com.flowzati.archone.inventory.location.entrypoint.rest.FacilityLocationRest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(FacilityLocationRest.class)
@DisplayName("Facility location HTTP surface")
class FacilityLocationRestTest {

    private static final UUID FACILITY_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ListStockLocationsUsecase usecase;

    @Test
    @DisplayName("列出 Facility 的多個 internal locations")
    void listsInternalLocations() {
        when(usecase.listInternalByFacility(FACILITY_ID))
                .thenReturn(List.of(
                        new StockLocationView(
                                UUID.fromString("00000000-0000-0000-0000-000000000021"),
                                FACILITY_ID,
                                "WH-NORTH/A",
                                "北部倉 A 區"),
                        new StockLocationView(
                                UUID.fromString("00000000-0000-0000-0000-000000000022"),
                                FACILITY_ID,
                                "WH-NORTH/B",
                                "北部倉 B 區")));

        var response = assertThat(mvc.get().uri("/facilities/{facilityId}/locations", FACILITY_ID));

        response.hasStatusOk();
        response.bodyJson().extractingPath("$.length()").isEqualTo(2);
        response.bodyJson().extractingPath("$[0].locationId").isEqualTo("00000000-0000-0000-0000-000000000021");
        response.bodyJson().extractingPath("$[0]").asMap().containsKeys("locationId", "facilityId", "code", "name");
    }
}
