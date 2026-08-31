package com.flowzati.archone.logisticsdata.application.usecase;

import com.flowzati.archone.logisticsdata.application.store.FacilityStore;
import com.flowzati.archone.logisticsdata.domain.aggregate.Facility;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ListFacilitiesForOwnerUsecase {

    private final FacilityStore facilityStore;

    public ListFacilitiesForOwnerUsecase(FacilityStore facilityStore) {
        this.facilityStore = facilityStore;
    }

    /**
     * 貨主是必要參數而非選用篩選。
     *
     * <p>與款、規格的理由不同——倉庫代碼不會跨貨主撞號。這裡的理由是**可用性**：一份不帶貨主
     * 的倉庫清單會誘使呼叫端提供該貨主出不了貨的倉，而那種訂單會被資料庫的複合外鍵擋下，
     * 換來一次沒有必要的往返。
     */
    public List<Facility> listByOwner(UUID ownerId) {
        return facilityStore.findByOwner(ownerId);
    }
}
