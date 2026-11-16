# Archone 系統分析文件網站

給面試官閱讀的 Bootstrap 靜態 HTML 文件網站。採淺灰側欄與外框、白色閱讀區及藍色選取標示。正文為 17px，表格為 15px。首頁為單欄專案說明與文件目錄；桌面使用左側導覽，手機使用可展開選單。

## 1 本機使用

需要 Node.js 22 以上。

```sh
cd sa_specs/pages
npm ci
npm run build
npm run preview
```

開啟 <http://127.0.0.1:4173>。結束預覽時按 `Ctrl+C`。`PORT=4180 npm run preview` 可改用其他連接埠。

請透過 HTTP 預覽；直接雙擊 `dist/index.html` 的 `file://` 模式無法正常載入 ES modules 與搜尋索引。

## 2 內容與互動

- 首頁：專案介紹、業務流程與文件目錄。
- 完整規格：依序呈現 01 業務範疇、02 BPMN、03 資料模型、04 資料字典、05 API、06 整合、07 BDD 規格案例、08 UAT、09 排障；章節採用 1、1-1 層級編號。
- BDD：由 `sa_specs/biz-bdd/` 的 7 個 feature 呈現 21 個業務情境，保留 Background、Given／When／Then 與資料表，支援案例搜尋及檔案下載。
- 圖表：17 張 Mermaid 圖在進入閱讀範圍時繪製；六張黑白 BPMN 提供 SVG 預覽及 BPMN／PNG／SVG 下載。
- 圖片檢視：獨立視窗、放大縮小、拖曳平移、滾輪及雙指縮放、重設與關閉。鍵盤可使用 `+`／`-`、方向鍵、`0` 及 `Esc`。
- 搜尋：全站規格章節、流程與 API 操作；使用搜尋按鈕或 `Ctrl+K`／`⌘K` 開啟。
- OPENAPI：使用官方 Swagger UI 5.34.0，依分類呈現 13 個操作，保留 GET／POST 色彩標籤、參數、請求範例、回應與 Schemas 展開閱讀，並提供原始 `openapi.yaml` 下載。
- 手機：收合導覽、長表格獨立橫向捲動、全螢幕圖表檢視。

版型參考 [Bootstrap 官方 Sidebars](https://getbootstrap.com/docs/5.3/examples/sidebars/)，使用 Bootstrap 5.3.8 的側欄、按鈕與表格樣式。Bootstrap CSS 與授權文件隨網站產物提供；手機選單與圖片檢視沿用既有互動。

所有腳本、樣式、圖檔與搜尋索引都包含在產物內，閱讀不依賴 CDN、後端或帳號。首次渲染 Mermaid 圖表會按需載入本地模組。

Swagger UI 僅在 OPENAPI 頁載入；JS、CSS、授權檔與由 `openapi.yaml` 產生的 JSON 都隨網站提供。此面試文件維持唯讀，關閉 Try it out 與外部線上驗證器。原有 `api.html#operationId` 搜尋／書籤連結會轉成 Swagger UI 原生深層連結，並展開指定端點。

## 3 更新文件

網站從 `sa_specs` 的 Markdown、OpenAPI 與 BPMN 圖檔產生。更新來源後，重新執行 `npm run build`；不直接修改 `dist`。

```text
sa_specs/
├── 00_*.md … 09_*.md       # 完整規格來源
├── openapi.yaml
├── bpmn/                  # BPMN 模型與 SVG／PNG
├── biz-bdd/               # 原樣複製的業務 Feature
└── pages/
    ├── src/
    │   ├── content.mjs    # 導覽、文件與流程描述
    │   ├── app.js         # 導覽、搜尋與圖表檢視器
    │   ├── diagrams.js    # Mermaid 按需繪製
    │   ├── openapi.js     # Swagger UI 與既有端點連結銜接
    │   └── site.css       # 桌面、手機與列印樣式
    ├── scripts/
    │   ├── build.mjs      # 靜態產生器、首頁與頁面模板
    │   ├── features.mjs   # Feature 情境與步驟轉換
    │   ├── check.mjs      # 連結、錨點、下載與搜尋驗證
    │   └── serve.mjs      # 本機預覽
    ├── tests/             # Playwright 瀏覽器驗證
    └── dist/              # 可部署產物，已排除於 Git
```

建置需要完整 `sa_specs` 目錄（含 `biz-bdd` 副本）；部署只需 `dist`。網站不產生原始碼追溯頁，也不複製後端程式檔案。

首頁文案位於 `../00_PROJECT_OVERVIEW.md`，BPMN 圖集位於 `../02_BPMN_PROCESS_MODELS.md`；導覽名稱與順序位於 `src/content.mjs`。新增文件時同步維護這些設定。文件內的相對連結會在建置時轉換為網站頁面或下載連結。`07_BDD_FEATURES.md` 的 `<!-- feature: 檔名 -->` 標記會在建置時展開對應 Feature；更新業務案例時先同步複製來源檔，再重新建置。

## 4 驗證

```sh
npm run build
npm run check
npx playwright install chromium  # 首次準備測試瀏覽器時
npm test
```

`check` 驗證所有本機連結、章節錨點、搜尋結果、圖表數量、索引表格、中文欄位說明覆蓋，以及 BDD 情境、步驟與下載內容完整性。瀏覽器測試涵蓋桌面／手機完整頁面、Mermaid 繪製、搜尋、深層連結、BDD 下載與搜尋、泳道圖上下層位置、圖片縮放與拖曳、雙指手勢、焦點返回及 320px 窄螢幕。Playwright 會自行啟動及關閉測試用預覽服務；失敗追蹤與截圖放在 `test-results`。

已有 Chromium 時，可設定 `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` 使用既有執行檔。

## 5 Cloudflare Pages 部署設定

網站已準備為純靜態輸出，目前尚未部署。

本專案已提供 [Terraform 與上傳設定](../terraform/README.md)，使用 Direct Upload：Terraform 建立 Pages 專案，固定版本的 Wrangler 上傳 `dist`，並支援選用的自訂網域。請優先依該文件操作；以下保留 Cloudflare 建置參數與其他部署方式供參考。

### 5-1 Git 整合

以 repository 根目錄作為建置根目錄，設定：

| 設定 | 值 |
| --- | --- |
| Framework preset | None |
| Build command | `npm ci --prefix sa_specs/pages && npm run build --prefix sa_specs/pages` |
| Build output directory | `sa_specs/pages/dist` |
| Node.js | 22 以上，例如環境變數 `NODE_VERSION=22` |

建置產物包含頂層 `index.html`、獨立頁面、`404.html` 及 `_headers`。各文件使用自己的 URL，可重新整理及直接分享章節連結；不需要 SPA fallback。Cloudflare Pages 可能將 `.html` URL 轉為不含副檔名的標準 URL，內容與相對連結均可正常存取。

設定依據：[Cloudflare 靜態 HTML 部署](https://developers.cloudflare.com/pages/framework-guides/deploy-anything/)、[自訂 Headers](https://developers.cloudflare.com/pages/configuration/headers/)。

### 5-2 本機產物上傳

也可先在本機建置，再用 Cloudflare Direct Upload 上傳 `dist`。如選擇 Wrangler，完成登入並確認目標專案後執行：

```sh
cd sa_specs/pages
npm ci
npm run build
npx wrangler pages deploy dist --project-name <your-project-name>
```

這是另一種部署方式；Cloudflare 對 Direct Upload 與 Git 整合專案的切換有限制，建立專案時先選定後續工作方式。參考：[Cloudflare Direct Upload](https://developers.cloudflare.com/pages/get-started/direct-upload/)。
