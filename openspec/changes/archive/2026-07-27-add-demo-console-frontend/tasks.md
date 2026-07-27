## 1. 專案骨架與後端連線

- [x] 1.1 實作 **The console reaches the backend through a development proxy**：於 `frontend/` 建立 React + TypeScript + Vite 專案，並依「以 Vite dev proxy 取代後端 CORS 設定」設定 dev server 將同源路徑轉發至後端，後端 origin 只出現在 dev server 設定、不出現在應用程式原始碼。行為上：瀏覽器發出的請求均為同源、不觸發 CORS preflight，後端無任何 CORS 設定。以啟動 dev server 後於開發者工具網路面板確認請求 origin 與無 preflight 驗證。
- [x] 1.2 實作「手寫 TypeScript 契約型別，不引入 OpenAPI 產生器」：於單一模組定義六支端點的請求與回應型別，涵蓋訂單表示、庫存狀態、補貨受理回應與組態回應，並於該模組註明它是唯一需跟隨後端合約變動之處。行為上：型別檢查涵蓋所有 API 呼叫點。以 `tsc` 無錯誤通過驗證。
- [x] 1.3 實作「不引入資料抓取函式庫——它會直接牴觸規格」與「動作狀態用 discriminated union，並由單一元件負責呈現」：定義泛型的 async 動作 hook，狀態型別以 `status` 區分 idle／pending／success／failure，讓「loading 同時有 error」這類不可能的組合在型別上就無法表達。行為上：任一動作失敗時狀態即為 failure 且帶有可呈現的訊息，成功時才帶 data。以該 hook 的單元測試驗證成功與失敗兩條路徑。
- [x] 1.4 依「樣式用 CSS Modules，不引入設計系統」與「元件不採用 compound component、render props 或 context」建立樣式與模組結構：CSS Modules 提供 scope 但不帶設計語彙；不建立 barrel file。行為上：元件之間的 class 名稱不會互相污染，且 import 路徑直接指向檔案實際位置。以 `npm run build` 與 `tsc` 通過驗證。

## 2. 頁面結構與導覽

- [x] 2.1 實作 **The console presents orders and stock as two navigable pages**：依「兩頁一個 router，且沒有訂單詳細檢視」建立訂單頁與庫存頁兩個路由，訂單頁為預設路由，並加入兩頁共用的頁首顯示由後端取得的目前分區策略。行為上：開啟根路徑進入訂單頁且頁首顯示生效中的策略；於兩頁間導覽時策略不重複請求；不存在任何訂單詳細檢視或 modal。以手動驗收在 `order-id` 與 `sku` 兩種後端設定下各確認一次驗證。

## 3. 訂單頁

- [x] 3.1 實作 **Placing an order shows the result in the list on the same page**：於訂單頁加入下單表單與最近訂單列表，送出成功後新訂單即出現於同頁列表；表單於送出前阻擋非正整數數量與空 SKU，此情況不發出請求。行為上：以有效輸入送出後列表顯示該訂單且狀態為 `PENDING`，且列上直接顯示訂單表示的全部欄位（含四個階段時間戳），不需要點開任何詳細檢視；數量為 0、負數或 SKU 為空時不發出請求。以元件測試涵蓋三種無效輸入、以手動驗收確認新訂單出現於列表驗證。
- [x] 3.2 實作 **Data is fetched only in response to a user action** 於訂單頁的部分：依「不輪詢：所有取數由使用者動作觸發」與「取數只在兩個進場點使用 effect，其餘一律在 event handler」，全應用只保留兩個 `useEffect`（訂單頁 mount 取列表、App mount 取分區策略），其餘取數都在 event handler；不安裝任何計時器或背景刷新，並提供明確的重新整理控制項——列表在後端完成非同步配置後即過時，沒有該控制項使用者只能重新載入整頁。行為上：訂單頁載入完成後靜置不再發出請求；按下重新整理後列表反映最新狀態。以手動驗收於開發者工具網路面板確認靜置期間無新請求驗證。

## 4. 庫存頁

- [x] 4.1 實作 **Stock state and replenishment share one page keyed by SKU**：於庫存頁提供 SKU 查詢與針對同一 SKU 的補貨觸發。行為上：on-hand 10／reserved 4 的 SKU 回報 available-to-promise 6；未知 SKU 渲染明確的查無此 SKU 狀態，而非看似零庫存的空結果；補貨成功後顯示已受理與事件識別碼，並說明配置結果須再次查詢才能觀察，不顯示任何配置已完成的訊息。依「庫存頁的結果要自報 SKU，且改動欄位就作廢」，兩種結果各自標出所屬 SKU（取自後端回應而非輸入框），且改動 SKU 欄位即作廢畫面上屬於前一個 SKU 的結果。以手動驗收涵蓋已知 SKU、未知 SKU 與觸發補貨三種情形、並以元件測試涵蓋結果歸屬驗證。

## 5. 失敗路徑與測試

- [x] 5.1 實作 **Backend failures are surfaced rather than swallowed**：以吃 async 狀態 union 的呈現元件統一渲染 pending 與 failure，成功才 render children，使「失敗必須被呈現」成為結構預設而非每處自律；不使用全域錯誤橫幅掩蓋來源，且失敗後不保留看似成功的輸出。行為上：補貨失敗時該區塊顯示失敗且畫面上不存在事件識別碼。以元件測試斷言失敗狀態下無成功跡象驗證。
- [x] 5.2 依「前端測試聚焦於有邏輯的部分」以 Vitest 搭配 React Testing Library 與 jsdom 建立測試設定並收斂測試範圍：測試對象限於表單提交前驗證與失敗路徑兩處，不對純版面元件撰寫測試。行為上：`npm test` 可重複執行且全數通過。以該指令通過驗證。

## 6. 端到端驗收與文件

- [x] 6.1 執行完整驗收劇本：在後端 dev profile 執行中的情況下，下單並於列表看到 `PENDING`；查詢一個 ATP 為 0 且有 backorder 佇列的 SKU；對其補貨並看到已受理與事件識別碼；回訂單頁按重新整理看到該批訂單轉為 `ALLOCATED` 且該列的 `allocatedAt` 有值。以手動操作逐項確認驗證。
- [x] 6.2 撰寫 `frontend/README.md`：說明啟動方式、它對後端 dev profile 的依賴（探針端點僅於 dev profile 註冊）、以及後端 origin 設定位於 dev server 而非原始碼。行為上：他人依此文件可從零啟動整套 demo。以照文件步驟實際執行一次驗證。
