package com.flowzati.archone.inventory.warehouse.domain.type;

/**
 * 位置的用途，決定它裡面的東西算不算公司的庫存。
 *
 * <p>只有 {@link #INTERNAL} 算。其餘三種是**虛擬位置**——它們存在，是為了讓每一次搬運的
 * 兩端都有地方可指：
 *
 * <pre>
 *   入庫      SUPPLIER  ──> 某倉的 INTERNAL
 *   出庫      某倉的 INTERNAL ──> CUSTOMER
 *   盤盈虧    INVENTORY ──> 某倉的 INTERNAL
 * </pre>
 *
 * <p>少了它們，「入庫」與「出庫」表達不成搬運——貨從供應商來、往客戶去，而那兩個地方不是
 * 本系統經營的倉。把兩者都模型化成位置之間的移動，全域總量才守恆，而守恆是「無法解釋的
 * 數量變動」得以被偵測的前提。
 *
 * <p><b>這是一組固定的值，不是設定。</b>每一個值都是程式邏輯的分支，因此以 enum 與資料庫
 * 的 CHECK 表達，而不是一張可維護的分類表——可設定的值域會讓「新增第五種用途」看起來像
 * 資料維護，實際上每個分支都要跟著改。
 */
public enum LocationUsage {

    /** 倉庫內部的實體位置。只有這種算公司持有的庫存。 */
    INTERNAL,

    /** 供應商。入庫的來源。 */
    SUPPLIER,

    /** 客戶。出庫的目的。 */
    CUSTOMER,

    /** 盤點調整。盤盈盤虧的另一端。 */
    INVENTORY;

    /** 虛擬位置不屬於任何倉——供應商與客戶不在本系統的倉庫清單裡。 */
    public boolean isVirtual() {
        return this != INTERNAL;
    }
}
