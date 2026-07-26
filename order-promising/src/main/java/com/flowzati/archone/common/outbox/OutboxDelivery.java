package com.flowzati.archone.common.outbox;

/**
 * 一則 Integration Event 的兩個傳輸決定：去哪個 Kafka topic（{@code route}）、
 * 用什麼 key 分區（{@code partitionKey}）。
 *
 * <p>把兩者收成一個型別，是為了讓「領域參數 vs 傳輸參數」的分界在
 * {@link OutboxAppender#append} 的簽章上就看得出來——這正是 {@code partition_key} 欄位
 * 存在的理由：傳輸決策不該借用 aggregate 欄位表達。
 */
public record OutboxDelivery(String route, String partitionKey) {
}
