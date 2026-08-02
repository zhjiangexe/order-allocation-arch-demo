package com.flowzati.archone.allocation.domain.model;

/**
 * 一段搬運走到哪了。
 *
 * <p>值域刻意只有四個，與 Odoo 的 {@code confirmed} / {@code assigned} / {@code done} /
 * {@code cancel} 一對一。三個沒有取的同樣重要，而且**理由分成兩種**：
 *
 * <ul>
 *   <li><b>沒有「等上一段」</b>（Odoo 的 {@code waiting}）——沒有上一段。它與「哪一段接在哪一段
 *       之後」的關聯表是同一件事的兩半，一起到來。
 *   <li><b>沒有「部分可用」</b>（{@code partially_available}）——ship-complete 下一張單整批配到
 *       或整批不配，部分可用不是一個會停留的狀態。
 *   <li><b>沒有「草稿」</b>（{@code draft}）——<b>這一個的性質不同</b>：前兩個在本系統的規則下
 *       不可能發生，草稿只是現在還沒有。Odoo 有它是因為單據可以先組出來、編輯，再按下按鈕才
 *       確認；本系統的搬運由收單事件建立，建立即已確認，中間沒有可編輯的階段。哪天出庫要先
 *       起草再放行，它就得回來——屆時要加的是狀態，不是另一張「草稿單」的表。
 * </ul>
 */
public enum MoveState {

  /** 要貨，還沒拿到。這就是待配佇列。 */
  CONFIRMED,

  /** 貨已鎖定。此時必有 move line 說明鎖了哪幾批。 */
  ASSIGNED,

  /**
   * 貨真的動了。
   *
   * <p><b>此階段沒有任何產生者</b>——出貨屬 R7。它現在就存在，是因為下游的謂詞要一次寫對：
   * 待配需求問的是「這條行有沒有 move」，而已完成的 move 也算有。少了它，R7 每一張已出貨的
   * 單都會重新變成待接手的需求，而**那一刻不會有任何測試失敗**。
   */
  DONE,

  /** 這一段不做了。取消時進入，其 move line 一併刪除。 */
  CANCELLED
}
