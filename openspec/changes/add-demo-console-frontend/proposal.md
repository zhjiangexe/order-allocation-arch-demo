## Why

`add-demo-console-api` 讓系統具備可被互動操作的 HTTP 表面，但目前沒有任何介面使用
它。要示範這個系統——下一張單、看它變成 `PENDING`、查 SKU 發現 ATP 為 0、補貨、
回頭看訂單轉為 `ALLOCATED`、點開看整條事件因果鏈——只能靠 curl 或 IDE 的 HTTP
client，無法在螢幕分享時讓觀看者跟上。

`frontend/` 目錄自 v1 起就存在且為空，v1 當時明確決定不做前端 UI。本次填上它。

## What Changes

- 在 `frontend/` 建立 React + TypeScript + Vite 應用，僅供本機開發環境執行。
- 兩個路由頁面：訂單頁（下單表單 ＋ 最近訂單列表 ＋ 訂單詳細 modal）與庫存頁
  （SKU 查詢 ＋ 補貨探針）。四個功能收斂為兩頁，因為「下單→看列表」與
  「查庫存→補貨→再查」各自是連續動作，拆開會讓一次操作跨頁跳轉。
- 共用頁首顯示目前生效的分區策略，讓觀看者知道跑的是 v1 還是 v3。
- 訂單詳細以 modal 呈現：上半為訂單狀態與各階段時間戳，下半為依發生時間排序的
  Integration Event 時間軸，payload 以泛型 key-value 呈現。
- 以 Vite dev proxy 連接後端，後端不新增任何 CORS 設定。
- 不做自動更新：所有資料取得皆由使用者動作觸發（進入頁面、送出表單、按重新整理、
  按查詢）。

## Non-Goals

- 不做登入、權限或多使用者。
- 不做訂單篩選、排序切換或真分頁；`limit` 由前端固定帶入。
- 不做取消訂單按鈕。`CancelOrderUsecase` 屬於 v1 核心範圍，與操作台無關。
- 不做輪詢、WebSocket、SSE 或任何形式的自動更新。
- 不做 production build、容器化或部署；此應用只在本機以 dev server 執行。
- 不做行動裝置版面；使用情境是桌面螢幕分享。
- 不引入設計系統或元件庫，不追求視覺打磨。這是操作台，不是產品介面。
- 不修改任何後端程式碼；後端合約由 `add-demo-console-api` 完整提供。

## Capabilities

### New Capabilities

- `demo-console-frontend`: 本機執行的兩頁操作台，讓下單、查庫存、觸發補貨、檢視
  訂單事件因果鏈這四件事可以在瀏覽器中完成與觀察，且所有資料取得皆可追溯到明確的
  使用者動作。

### Modified Capabilities

- None.

## Impact

- 依賴 `add-demo-console-api`：本應用消費該 change 提供的六支端點，沒有它則無可用
  後端合約。
- 新增 `frontend/` 下的 Node 專案：`package.json`、Vite 設定、TypeScript 設定、
  原始碼與元件測試。此為本 repo 首次引入 Node 工具鏈；Gradle build 不與之整合，
  兩者各自獨立執行。
- 後端零改動。CORS 由 Vite dev proxy 處理，因此不會有寬鬆的 CORS 設定殘留在後端
  程式碼中。
- 文件：新增 `frontend/README.md` 說明啟動方式與它對後端 dev profile 的依賴。
