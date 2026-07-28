# frontend

`order-promising` 的操作台：兩頁、本機執行、給螢幕分享時邊操作邊解說用。它不是產品
介面，也不打算變成產品介面。

## 啟動

需要後端**以 dev profile 執行**——`/demo/replenish` 與 `/demo/config` 只在 dev profile
註冊，其他 profile 下這兩條路徑會回 404，操作台的補貨與策略顯示就沒有東西可用。

最省事的方式是用 `e2e/perf` 那套（PostgreSQL + Kafka + Debezium 都會一起起來）：

```bash
./e2e/perf/run.sh up      # 基礎設施 + app（dev profile）+ Debezium connector
cd frontend && npm install && npm run dev
```

開 http://localhost:5173 （被佔用時 Vite 會自動換埠，看它印出來的那行）。

**不要用 `docker compose up` 代替 `run.sh up`。** app 不在 compose 裡，而 Debezium
connector 必須等 app 跑完 Flyway 建出 `event_outbox` 才能註冊——少了那步，下單會成功、
`OrderPlaced` 卻出不了 outbox，訂單就永遠停在 `PENDING`，畫面上看不出任何錯誤。

後端位址只寫在 `vite.config.ts` 的 dev proxy 裡，不出現在任何原始碼中。要指到別的地方
設環境變數即可，不用改元件：

```bash
ARCHONE_BACKEND_ORIGIN=http://localhost:9090 npm run dev
```

## 指令

| 指令 | 用途 |
| --- | --- |
| `npm run dev` | dev server（含後端 proxy） |
| `npm test` | Vitest 元件與 hook 測試 |
| `npm run typecheck` | `tsc --noEmit` |
| `npm run build` | 型別檢查 + production build（本專案不部署，用來確認建置沒壞） |

## 兩頁在做什麼

**訂單頁**（預設）——下單表單 ＋ 最近 20 筆訂單。列表一列攤開訂單的全部欄位，包含四個
階段時間戳；沒有「點開看詳細」，因為那會是同一份資料的第二次呈現。

**庫存頁**——查一個 SKU 的 on-hand／reserved／available-to-promise，並對同一個 SKU 觸發
補貨。兩者共用一個 SKU 欄位，因為它們是一個連續動作：查到 ATP 是 0、補一批、再查一次。

頁首顯示後端目前生效的分區策略（`sku` 是 v3 single-writer，其餘是 v1）。只顯示不切換
——那個值在後端啟動時就固定了。

## 主檔載入的代價

兩頁都在進場時載入整份主檔（`useCatalog`），因為訂單列表要把 `ownerId` 與 SKU 代碼還原成
看得懂的字，而訂單契約刻意只帶 `ownerId`——名稱從畫面本來就要載的那份主檔解析即可，那是
「整個畫面查一次」，不是每一列各查一次。

代價是請求數為 `1 + N貨主 + Σ款` 的 fan-out：目前的 demo 資料（2 貨主、3 款）是 6 支。這個
數字隨主檔線性成長，而它是下單表單能用的前提。

要收掉得由後端提供一支扁平的主檔查詢（一次回攤平的 owner／product／sku），把 6 支收成 1 支。
**現在不做**：這是效能問題不是正確性問題，而且現有三支端點的資源劃分（款與規格巢狀在貨主
之下，因為編碼跨貨主撞號、貨主是它們存在的前提）是對的——扁平端點會是額外的讀取視圖，不是
取代它們。等主檔規模真的讓這個 fan-out 會痛時再做，屆時要一併決定兩者如何並存。

## 為什麼沒有自動更新

所有請求都對應一個明確的使用者動作：進頁面、送出表單、按查詢、按重新整理。沒有輪詢、
沒有計時器、沒有背景刷新——訂單頁載入完成後靜置，網路面板不會再出現任何請求。

代價是補貨之後要自己按「重新整理」才看得到訂單狀態變化。這在「操作者本人邊操作邊解說」
的情境下不是負擔，反而讓「配置是非同步的」這件事在操作上顯而易見。

## 一個典型的 demo 流程

用 `SKU-EMPTY`（ATP 是 0）：

1. 庫存頁查 `SKU-EMPTY` → on-hand 0、ATP 0
2. 訂單頁對它下幾張單 → 看到 `PENDING`，按重新整理 → 變成 `BACKORDERED`
3. 庫存頁選好貨主、對它補貨 → 顯示「已受理」與事件識別碼（**不是**「已配置」）
4. 訂單頁按重新整理 → 看到那批訂單依 FIFO 轉成 `ALLOCATED`、`allocatedAt` 有值

種子已經在乙貨主的佇列裡放了一張 `SKU-EMPTY` 的缺貨單，`backordered_since` 比你當場下的
都早——補乙貨主的貨時它會**排在最前面**被配到。那不是 bug，是 FIFO 本來的樣子。

補貨要選貨主，而佇列是按貨主分開的：補甲貨主不會動到乙貨主的單，即使兩邊的 SKU 代碼相同。
