package com.flowzati.archone.ordering.domain.event;

/**
 * 事件裡那一行的內容：行號、SKU 與數量。
 *
 * <p>是 {@code OrderLine} 在事件發生當下的投影，不是它本身——刻意不帶 {@code status} 與
 * 缺貨等待不是行的狀態；事件本身已經說明了發生什麼事。
 *
 * <p>三個帶行的事件（下單、缺貨、取消）平等引用這個型別，而不是共用其中一個事件的內部
 * record：那樣會讓取消事件裡出現「下單事件的行」這種說謊的名字，也會讓其中一個事件加欄位
 * 時另外兩個被迫跟著長出不需要的欄位。
 */
public record LineSnapshot(int lineNo, String skuCode, int quantity) {
}
