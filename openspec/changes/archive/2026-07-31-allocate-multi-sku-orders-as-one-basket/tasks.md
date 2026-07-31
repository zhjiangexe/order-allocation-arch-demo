## 1. 額度與計畫的型別

- [x] 1.1 依 design 的決策「餘量與需求收成具名型別」，新增 `allocation/domain/service/SkuQuantities`：SKU → 數量的不可變映射，帶 `covers(SkuQuantities)`、`minus(SkuQuantities)`、`quantityOf(String)`、`skuCodes()`。行為上：扣減後為負即拋錯。以領域測試涵蓋涵蓋／不涵蓋／扣減／扣成負數四種情形驗證。

  `availableBySku`、policy 的餘量、`shortfallBySku` 三處都是同一個形狀，而它們身上有共同的不變式：數量非負、扣減不得為負、涵蓋檢查要對**每一個** SKU 成立。裸 `Map<String, Integer>` 會讓那些操作散在迴圈裡——這個 repo 已經有相反的做法（`StockContentionKey`、`requireSingleSku()`）。

- [x] 1.2 新增 `allocation/domain/service/AllocationPlan`：`(List<BatchPick> picks, SkuQuantities shortfall)`，帶 `isFeasible()`。行為上：不可行時 `shortfall` 說得出每個 SKU 差幾件。以領域測試驗證。

  **不再以空清單表示失敗。** 那是隱含約定，而多 SKU 之後「哪個 SKU 差幾件」是操作上必要的資訊。R3 當時把「為什麼配不到」推給庫存頁回答（`expired` 與 `availableToPromise` 合起來看），那個判斷在單 SKU 下成立——多 SKU 之後使用者得逐一查每個 SKU 才拼得出「這張單卡在哪」。

  `AllocationOutcome` 不動：它回答訂單層級的「沒得配」與「不夠配」，shortfall 回答「差在哪」。合併會讓 enum 變成無界的。

## 2. 配貨演算法

- [x] 2.1 依 `stock-allocation` 的 **Allocatable stock is supplied to allocation grouped by SKU**，`AllocationService.allocate` 的批次參數由 `List<StockPool>` 改為 `Map<String, List<StockPool>>`。行為上：需求的某個 SKU 沒有對應的鍵時拋錯，多出來的鍵則接受。以領域測試涵蓋「缺一個 SKU」與「多一個 SKU」兩種情形驗證。

  `requireBatchesCoverDemand` 檢查的是**涵蓋**而不是恰好相等——喚醒傳進來的是整輪候選單的 SKU 聯集，恰好相等會把一張只要 A 的單擋在門外。多出來的鍵擋不到任何錯（`planPicks` 只按 `line.skuCode()` 取用），真正危險的方向只有「鍵少了」：它會經 `getOrDefault` 安靜地變成缺貨。

- [x] 2.2 依同一個 requirement，**某個 SKU 沒有可配批時以空群組表達，不是缺鍵**。行為上：空群組是缺貨（正常結果），缺鍵是呼叫端組錯輸入（拋錯）。以領域測試驗證兩者導向不同結果。

  合併它們會讓程式錯誤與業務結果分不開。

- [x] 2.3 依 **A multi-SKU order is satisfiable only when every one of its SKUs is**，`planPicks` 改回傳 `AllocationPlan`：逐行以 `line.skuCode()` 取對應的批，累計每個 SKU 的取用與缺口。行為上：一個 SKU 不足時整張單不預留，且缺口列出**每一個**不足的 SKU。以領域測試涵蓋「一個不足」「兩個不足」「全部足夠」驗證。

  **不短路。** 停在第一個不足的 SKU 比較快，但回報的缺口會取決於檢查順序——而問「這張單在等什麼」的人需要全部。

- [x] 2.4 `planned` 的累計改為 per-SKU：同一張單的第二行看到的可用量要扣掉第一行已規劃的。行為上：同 SKU 兩行、庫存只夠一行時整張單不配。以領域測試驗證。

  **這一項在單行下完全碰不到**，而它錯了的症狀是超賣：兩行各自看到未扣減的可用量，都算得出「夠」，然後其中一行在 `reserve()` 時拋錯——或更糟，兩行都成功。

## 3. 挑單政策

- [x] 3.1 依 design 的決策「餘量是 per-SKU 的映射，而且 policy 拿不到『那個 SKU』」，`AllocationRequest` 由 `(sku, availableToPromise, decisionAt)` 改為 `(SkuQuantities availableBySku, Instant decisionAt)`。行為上：policy 寫不出「只檢查某一個 SKU」的版本。以編譯與既有測試驗證。

  **刻意不保留 `triggeringSku`。** 那是事件的屬性不是決策的屬性；留著就會有人拿它寫出逐 SKU 獨立判斷的版本——而那正是這個 change 要消除的行為。

- [x] 3.2 依 `stock-allocation` 的 **Waking a queue loads every SKU its candidates need**（該 requirement 的 head-of-line 段落），`StrictFifoAllocationPolicy` 的餘量改為 `SkuQuantities`，`break` 的判準由「這個 SKU 不足」改為「**任一** SKU 不足」。行為上：隊首因為別的 SKU 卡住時，後面配得到的單也不配。以「隊首缺 B、後面只要 A 且 A 充足」的測試驗證，斷言兩張都沒配到。

  **仍是 `break` 不是 `continue`。** 改成 continue 會讓隊首在後面不斷有小單時永遠等下去。若吞吐成為問題，緩解是**給隊首保留額度**（佔住它需要的量、後面的用剩下的），先來先服務因此仍然成立——而不是放棄順序。這一點寫在這裡，因為 `break` → `continue` 是一個看起來只有一個字的改動。

- [x] 3.3 `MaximizeFulfilledOrdersPolicy` 的排序鍵改為整籃的度量，並在 Javadoc 寫明選了哪一個與為什麼。行為上：多 SKU 訂單排得出順序。以測試驗證。

  design 的 Open Question：候選是「總件數最少」「涉及 SKU 數最少」「最稀缺 SKU 的需求量最小」。它**沒有生產呼叫端**（只有測試），所以不阻塞——但不能默默選一個而不寫理由。

## 4. 收單與呼叫端

- [x] 4.1 依 `order-intake` 的 **Order intake accepts one or more lines**，移除 `Order.place()` 的「恰好一行」限制，保留「至少一行」。行為上：兩行不同 SKU、兩行同 SKU 都被接受；零行仍拒絕。以領域測試驗證三種情形。

- [x] 4.2 `AllocateOrderUsecase` 移除 `requireSingleSku`，改為取需求涉及的所有 SKU 的批並以 SKU 分組傳入。行為上：多 SKU 的下單事件配得動。以單元測試與 SIT 驗證。

- [x] 4.3 `StockPoolRepository` 新增「一次取多個 SKU 的可配批」的查詢，回傳已依 SKU 分組。行為上：查詢次數不隨 SKU 數成長。以 SIT 斷言排序在每個 SKU 內仍是 FEFO 驗證。

## 5. 補貨喚醒

- [x] 5.1 依 `stock-allocation` 的 **Waking a queue loads every SKU its candidates need**，`ReplenishmentUsecase` 的取批接上第三段：收集候選單涉及的所有 `skuCode`，一次查回。行為上：候選單的其他 SKU 也依它自己的庫存判斷。以 SIT 驗證「補 A 之後，缺 B 的單仍不配，且 A 未被預留」。

  **查詢次數固定為三次**（選單 → 取行 → 取批），不隨候選單數成長。逐張各自查會是 N+1，而一輪最多 200 張。

- [x] 5.2 **死鎖排序鍵的前提要維持**：本輪要碰的 `StockPool` 集合必須在進入交易前全部已知。行為上：`WRITE_ORDER` 算得出來。以程式碼審閱驗證。

  R3 的喚醒上限讓候選單有界，5.1 讓池的集合有界。逐張邊查邊配的話，那個集合要到配到一半才知道——而 `WRITE_ORDER` 是防死鎖的唯一手段。

## 6. 前端

- [x] 6.1 依 `demo-console-frontend` 的 **The order form composes a basket of several lines**，下單表單加行編輯器：可新增／移除行，每行各自選款與規格、填數量。行為上：一張單可以送出多行。以前端測試涵蓋新增、移除、最後一行不可移除三種情形驗證。

- [x] 6.2 換貨主時清空**所有**行。行為上：不可能送出屬於別的貨主的商品。以前端測試驗證。

- [x] 6.3 任一行不完整即擋下送出。行為上：不完整的請求不會換來一次沒有必要的往返。以前端測試驗證「一行完整、一行缺規格」時不發請求。

- [x] 6.4 訂單列表逐行顯示商品與數量。行為上：多行訂單被掛帳時，看得出是哪一行卡住。以前端測試驗證。

  列表的 requirement 早就寫著「顯示訂單表示的每一個欄位」且「每一列渲染它的行」，所以這一項多半已經成立——**要確認而不是假設**。

## 7. 驗收

- [x] 7.1 依 `stock-allocation` 的 **A multi-SKU order is satisfiable only when every one of its SKUs is**，SIT 驗證「A×10 + B×5，B 只有 3 件 → 兩行都不預留、整張缺貨、A 的預留量不變」。行為上：整籃原子性在真實的資料庫路徑上成立。

- [x] 7.2 種子加一張多 SKU 的訂單，其中一個 SKU 刻意不足。行為上：操作台一打開就看得到「有貨卻不配」。以 seed SIT 驗證。

- [x] 7.3 **確認 R4 消除的超賣路徑仍然關著**：一張單兩行同 SKU，經佇列查詢後只產生一個 `Demand`、一份加總的需求。行為上：不會扣兩次。以 SIT 驗證。

  roadmap 的 R8 任務 6 描述的是 `findBackordersBySkuInFifoOrder` 缺 `DISTINCT` 導致同一張單出現兩次。**R4 移除了那支查詢**，取代它的兩段式查詢在結構上排除了這件事——但那是 R4 的副作用而非它的目標，所以要有一支測試明確守著。

- [ ] 7.4 後端 `test` 與 `sit`、前端 `vitest` 與 `tsc` 全綠，`spectra validate --strict` 通過；壓測的正確性三條（checks、不超賣、決策逾時 0）維持。

## 8. 收尾

- [x] 8.1 更新 `docs/execution-roadmap.md` 的 R8 一節：任務 4、6、7 已由 R3／R4 解決或消失，實際範圍是演算法而非結構。行為上：roadmap 不再描述已經不存在的工作。

- [x] 8.2 把「head-of-line blocking 的緩解是保留額度而非跳過」寫進 roadmap 的「已識別但未排程」。行為上：下一個想優化吞吐的人不會直接把 `break` 改成 `continue`。
