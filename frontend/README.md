# Allocation 操作台

使用既有 API 建單、查看配貨等待、補貨並追蹤到履約完成。同一前端支援 Events 與 Temporal；
模式由後端啟動設定決定。範圍為 allocation 正常履約，不提供取消或手動 WMS 操作。

## 本機啟動

從 repository root 選擇一種模式：

```bash
# Events
ARCHONE_MANAGEMENT_PORT=28298 make dev-up

# 或 Temporal（包含本機 Temporal server）
ARCHONE_MANAGEMENT_PORT=28298 make dev-up-temporal
```

再啟動前端：

```bash
npm --prefix frontend install
npm --prefix frontend run dev
```

開啟 http://localhost:28295 。標準 Compose management port 原為 28295，與 Vite 衝突，
所以上述命令將 management port 改為 28298；後端 API 仍為 28290。Vite 使用 strictPort，
埠被占用會直接失敗。已啟動後端時只需啟動前端；也可指定其他前端埠與後端：

```bash
ARCHONE_BACKEND_ORIGIN=http://localhost:28490 \
npm --prefix frontend run dev -- --port 28495
```

`make dev-up*` 會等待 app migration 完成並註冊 Debezium connector。只啟動資料庫與 Kafka
不足以推進流程。模式切換限制見 [部署說明](../docker/README.md)。
前端以 `/api` 相對路徑請求，由 Vite dev proxy 轉送；production hosting 需另提供對應代理及 SPA fallback。

| 指令（在 frontend 執行） | 用途 |
| --- | --- |
| `npm run dev` | 本機前端與 API proxy |
| `npm test` | 元件與 hook 測試 |
| `npm run typecheck` | TypeScript 檢查 |
| `npm run build` | 型別檢查與 production build |

## 四個功能頁面

| 路徑 | 操作與限制 |
| --- | --- |
| `/orders` 訂單 | 按「新建訂單」開啟視窗，填妥多 SKU 表單後「送出」或「取消」；最近 20 筆訂單、手動更新及「查看履約」。最晚離倉時間必填，依瀏覽器本地時區輸入；release priority 預設 0，範圍 0～100。成功後開啟詳情。 |
| `/allocations` 配貨佇列 | 查詢 `GET /stock-operations?state=CONFIRMED&limit=200`，於已載入資料篩選貨主、倉別、來源及 SKU。提供手動更新、有效 ORDER／PRIMARY 訂單的履約入口與補貨導航。 |
| `/stock` 庫存 | 按貨主、倉別、庫位查詢；SKU 彙總、展開批次、收貨及手動重新查詢。從佇列帶入的參數經主檔驗證，返回時保留原篩選。 |
| `/catalog` 主檔 | 唯讀查看貨主、倉別、庫位、商品及 SKU，保留代碼與 UUID 供核對。 |

佇列呈現等待中的庫存作業，不是精確缺口、全域排名或 FIFO blocker 查詢；最多顯示 200 筆，
本地篩選不能代表全部後端資料。配貨仍依後端政策執行；補貨不保證優先分給目前查看的訂單。
前序訂單阻擋的改善由使用者決定另案處理。

主檔載入先查貨主，再按貨主載入倉別與商品、按倉別載入庫位、按商品載入 SKU。
請求數隨主檔成長；目前沒有新增扁平主檔 API。

建單與庫存欄位只有一個有效選項時會自動帶入，包含非同步載入與上下層連動；不會自動送出或查詢。
配貨篩選的「全部」仍保留，已選值與鎖定表單不改動。

各頁下拉選單使用共用 Select 元件，支援滑鼠、方向鍵、Home／End、輸入文字定位、Enter 選取與 Escape 關閉。
新建訂單送出期間不能關閉視窗；結果不明時取消只收起視窗，再按「繼續確認訂單」可恢復原資料。

## 查看履約與自動追蹤

詳情抽屜可由 URL 深連結恢復，按「關閉詳情」、Escape 或側邊欄外的遮罩即可關閉並回原頁。分區顯示訂單、庫存作業、movement／批次、Shipment
及技術資訊。狀態橫列列出訂單狀態，當前 `order.status` 使用綠色，滑鼠移入可看說明。
該狀態與整體履約完成指標不同，跨 Context 更新可能暫時有時間差。

整體完成會核對 Order `FULFILLED`、ORDER／PRIMARY 出庫作業 `DONE`、對應 Shipment
`HANDED_OVER_TO_CARRIER`，以及訂單、作業、Shipment 的關聯身分。
Temporal 還需 Workflow `FINISHED`／`FULFILLMENT_COMPLETED` 與相關 IDs、交運結果一致。
僅有 `ALLOCATED` 表示完成分配，仍須執行出庫與履約回寫。

- 開啟詳情立即查詢，前次請求結束後約 2 秒再次查詢，不重疊請求；列表與佇列不自動輪詢。
- 「暫停」停止後續排程，在途請求仍可完成。「重新整理」執行一次查詢，不解除手動暫停。
- 「繼續追蹤」重新查詢並依新結果判斷是否繼續；成功完成、未知狀態或外部取消會停止追蹤。
- 查詢錯誤或 Workflow `UNAVAILABLE` 會停止並保留最後資料與更新時間；可手動重試。
- `NOT_FOUND` 不表示流程完成，也不保證稍後一定啟動，會繼續追蹤。
- 分頁進入背景會取消在途請求並暫停，回前景僅於未手動暫停、未停止時恢復。
  關閉詳情或卸載會取消請求並清除排程。

## 收貨與重試

庫存列出主檔 SKU，即使該庫位沒有庫存仍可收貨；庫存有資料但主檔未找到的 SKU 也不隱藏。
批次展開保留過期資料，四個彙總數字如下：

| 欄位 | 計算 |
| --- | --- |
| 在手 | 全部批次 on-hand，含過期 |
| 已預留 | 全部批次 reserved |
| 可承諾 | 未過期批次 ATP |
| 已過期 | 過期批次 on-hand |

收貨視窗固定查詢的貨主、倉別、庫位及該列 SKU，填入入庫日、效期與數量。
`POST /stock-receipts` 成功表示同步入庫完成；畫面提供重新查詢，不直接改寫庫存快照。
補貨會喚醒等待作業，因此可承諾量不一定增加。

結果未確認時保留原 `receiptId` 與 payload，以「重試原收貨」重送，避免重複入庫。
待確認命令期間有離頁提醒；明確開始新操作才產生新 ID。此暫存不跨瀏覽器重新載入保存。
建單重複的 ownerId＋externalOrderNo 回 409，不是回傳原訂單的冪等 API；
遇到結果不明時先查訂單，避免換新單號盲目重送。

## Temporal Workflow 連結

「查看履約」依後端 `orchestrationMode=temporal` 顯示「在 Temporal UI 查看 Workflow」，
於新分頁開啟 `order-fulfillment/{orderId}`；Events 不顯示。查詢不可用或 `NOT_FOUND` 時仍保留入口，
連結不代表 Workflow 已存在。

在 `frontend/.env.local` 設定（範本 [.env.example](.env.example)）：

```dotenv
VITE_TEMPORAL_UI_URL=http://localhost:28296
VITE_TEMPORAL_NAMESPACE=default
```

預設如上。隔離環境須改成對應 UI port／namespace。位址須為瀏覽器可存取的 HTTP(S) URL，
可包含反向代理路徑前綴；無效設定會顯示提示。這些是公開前端設定，不可放密碼。
修改後重啟 Vite，production 須重新 build；此設定只決定跳轉位址，不切換後端模式。

## 重現驗收

1. 在獨立模式環境準備未消耗的 UI fixtures，核對初始庫存。
2. 訂單頁建單並查看履約；有貨單等待出庫及回寫完成。
3. 缺貨單由配貨佇列前往補貨，收貨後返回原篩選並重新查看同一筆履約。
4. 多 SKU 單先確認未部分預留，再補足不足 SKU；FEFO 單核對有效批各取 2、過期批未消耗。
5. Temporal 模式再確認 Workflow 成功終態並跳轉 Timeline。

完整命令、隔離環境、八筆訂單 IDs 與證據界線見 [T7 驗收紀錄](../docs/plans/allocation-console/validation.md)。
fixtures 使用 `ON CONFLICT` 不等於重置庫存；重跑請用全新 project／資料庫，不能把已消耗資料當初始值。
