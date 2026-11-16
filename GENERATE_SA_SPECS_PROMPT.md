# Role & Mission
你是一位精通「製造支援與企業系統整合」的頂尖系統分析師（Senior Lead System Analyst）。
請深入掃描與分析目前專案的程式碼庫（包括 API Controller 路由、Service 業務邏輯、Model/Entity 結構、資料庫 Migration/Schema、設定檔以及測試案例），逆向工程為本專案建立一套專門對齊製造業資訊處標準的「系統分析與整合規格套件（SA Specification Bundle）」。

---

## 核心格式準則（大家最喜歡的優秀文件標準）
產出的文件必須嚴格遵守以下四項業界最佳實務，嚴禁交付空洞的流水帳小說，使用的語言要簡明清晰：

1. **讀者分層原則（Audience-Targeted）**：依業務、整合、資料與維運角色安排內容，不重複列出讀者標籤或文件生成說明。
2. **圖文並茂原則（Diagram as Code + Deep Explanation）**：除 `openapi.yaml` 為標準純 YAML 外，業務、整合、資料與驗收文件採用 **「上方 Mermaid 向量圖（GitHub 原生渲染）+ 下方結構化表格/條列深入說明」**。嚴禁只貼一張圖沒有文字說明。
3. **表格化防呆規範（Poka-Yoke & RFC 9457）**：所有異常與防呆檢核一律採用清晰的「表格化矩陣」，錯誤響應以現有合約為準；支援 IETF RFC 9457 Problem Details 的端點依標準描述，例外格式須明列。
4. **真實程式碼對齊（Code-to-Spec Grounding）**：所有模型、端點、欄位名稱與狀態列舉，必須 100% 來自本專案真實程式碼，嚴禁使用 `foo/bar` 或隨意瞎編的假資料。

---

## 交付目錄與檔案架構

請在專案根目錄建立 `sa_specs/` 資料夾，並嚴格產出以下 **10 個核心檔案（9 份 Markdown + 1 份獨立 OpenAPI YAML）**。BPMN 模型、預覽與相關說明集中在其下的 `bpmn/` 子資料夾，另計為流程附件：

```
sa_specs/
├── 00_PROJECT_OVERVIEW.md           # 專案說明與文件目錄
├── 01_BUSINESS_FLOW_AND_SCOPE.md    # 業務範疇與狀態
├── 02_BPMN_PROCESS_MODELS.md        # 六張黑白 BPMN、例外處理對照
├── 03_DATA_MODEL_AND_STORAGE.md     # 資料關聯、約束、索引與並行控制
├── 04_DATA_DICTIONARY.md            # 欄位中文說明與型別
├── openapi.yaml                    # 05 API 合約
├── 06_INTEGRATION_AND_SEQUENCE.md   # 整合與交易時序
├── 07_BDD_FEATURES.md               # 業務情境索引
├── 08_UAT_AND_VERIFICATION.md       # 驗收步驟與紀錄
├── 09_TROUBLESHOOTING_RUNBOOK.md     # 診斷與復原
├── biz-bdd/                        # e2e/biz-bdd/features 的原樣副本
├── bpmn/                           # BPMN、SVG、PNG 與流程規格
└── pages/                          # Bootstrap HTML 網站
```

文件導航放在 `00_PROJECT_OVERVIEW.md`，列出十份文件的閱讀目的與相對連結。
十份核心文件採用上述平面結構，BPMN 附件集中在 `bpmn/`；同一主題維持一份權威內容，其他文件透過連結引用。資料字典按 context 分節，保留在單一檔案中。

---

## 各檔案詳細內容與格式規範

### 0. `00_PROJECT_OVERVIEW.md`
- 單欄專案說明、業務流程與文件目錄，不加入統計卡片或重複導讀。

### 1. `01_BUSINESS_FLOW_AND_SCOPE.md`
- **主要讀者**：業務主管、生管主管、專案經理 (PM)
- **1. 系統目標與範疇表格（Scope Matrix）**：
    - 條列本系統的商業痛點與價值定位。
    - 清楚對比表格：`In-Scope（本系統核心職責）` vs `Out-of-Scope（外部系統/人工作業邊界）`。
- **2. 跨系統泳道摘要與 BPMN 模型對照**：
    - 以 Mermaid (`flowchart TD` 或 `LR`) 繪製。
    - 依實際責任劃分作業人員、操作介面、核心服務、上游與資料基礎設施，不加入未實作的設備整合。
    - 包含 Happy Path 與異常分流路徑。
    - 下方搭配 **角色權責矩陣表（RACI Table）** 與 **線下異常人工處置 SOP**。
    - Mermaid flowchart 是閱讀摘要；正式 BPMN 2.0 XML 模型須明確區分。若專案已有 `sa_specs/bpmn/`，讀取其索引、流程規格與模型，於 `02_BPMN_PROCESS_MODELS.md` 提供流程對照及 `.bpmn`／PNG／SVG 相對連結，本文件只提供連至圖集的入口。
    - 沿用既有 BPMN 模型位置，SA 文件維持十檔；模型缺失時明列缺口。依實際 `isExecutable` 與部署配置說明分析用途或執行用途，不以圖檔存在推定可部署到流程引擎。
- **3. 核心實體生命週期狀態機（State Machine）**：
    - 以 Mermaid (`stateDiagram-v2`) 繪製專案最關鍵業務（如工單、物料批次、任務）之生命週期。
    - 下方搭配 **狀態躍遷防呆矩陣表**：
      | 當前狀態 (From) | 觸發事件 (Event) | 前置防呆條件 (Guard) | 目標狀態 (To) | 系統處置動作 | 異常攔截代碼 |
      | :--- | :--- | :--- | :--- | :--- | :--- |

---

### 2. `02_BPMN_PROCESS_MODELS.md`
- 集中六張黑白 BPMN、下載連結與符號說明，連至細節流程規格。
- 不另外列出 WMS 取消判斷分圖。

### 3. `03_DATA_MODEL_AND_STORAGE.md`
- **主要讀者**：資料庫管理員 (DBA)、後端研發工程師
- **1. 實體關係圖（Entity Relationship Diagram）**：
    - 以 Mermaid (`erDiagram`) 繪製核心 Table/Entity 關聯（標出 `||--o{` 關係、PK、FK）。
- **2. 資料責任與一致性**：
    - 標示跨系統資料來源（Source of Truth）、實體 FK 與邏輯 correlation 的區別。
    - 說明 View 用途、跨表約束與狀態防呆；完整欄位定義連結至 `04_DATA_DICTIONARY.md`。
- **3. 儲存優化與並行控制**：
    - 根據程式碼查詢語句，規劃高頻查詢之 **複合索引（Composite Indexes）**。
    - 並行寫入防衝突機制（樂觀鎖版本號 `version` vs 悲觀鎖 `FOR UPDATE`）。

---

### 4. `04_DATA_DICTIONARY.md`
- **主要讀者**：資料庫管理員 (DBA)、後端研發工程師、資料整合工程師
- 開頭提供 Mermaid 資料責任分組圖與可點擊目錄，按專案真實 context 分節。
- 每張表及 View 條列完整欄位：欄位名稱、中文業務說明、型別、PK/FK、Null 限制、預設值、業務約束（Unique/Check）。View 的推導欄位標註來源與不適用的儲存限制。
- 保留精確約束定義；跨欄位約束須整組解讀。不列原始碼路徑、來源追溯表或逐表 migration 出處。
- ERD、索引及並行策略引用 `03_DATA_MODEL_AND_STORAGE.md`。

---

### 5. `openapi.yaml`
- **主要讀者**：前端工程師、外部整合廠商、自動化測試工具
- **獨立標準的 OpenAPI 3.1 YAML 檔案**（非 Markdown）。
- 嚴格定義專案核心端點的路徑、請求參數格式、欄位 Regex 驗證。
- 僅定義實際支援的 Header；不新增未啟用的冪等 Header 提案。
- 錯誤回傳結構完全遵守 **RFC 9457 Problem Details**：
  ```yaml
  ProblemDetails:
    type: object
    required: [type, title, status, detail]
    properties:
      type: { type: string, format: uri }
      title: { type: string }
      status: { type: integer }
      detail: { type: string }
      instance: { type: string }
      code: { type: string }
      invalid_params:
        type: array
        items:
          type: object
          properties:
            name: { type: string }
            reason: { type: string }
  ```

---

### 6. `06_INTEGRATION_AND_SEQUENCE.md`
- **主要讀者**：後端工程師、系統分析師 (SA)、設備端韌體工程師
- **1. 跨系統通訊循序圖（Sequence Diagram）**：
    - 以 Mermaid (`sequenceDiagram`) 繪製最關鍵的業務串接時序。
    - 標示具體協議（如 `HTTP POST /v1/...`、`SELECT FOR UPDATE`、`MQTT Pub`）。
    - 嚴格使用 `alt/else`（驗證通過 vs 防呆攔截）與超時處理。
- **2. 表格化防呆規則矩陣（Poka-Yoke Matrix）**：
    - 整理專案代碼中的真實檢核邏輯：
      | 檢查項目 | 觸發端點 | 檢驗邏輯（防呆條件） | 判定結果 | 實體/介面處置動作 | 錯誤代碼 (RFC 9457) |
      | :--- | :--- | :--- | :--- | :--- | :--- |
- **3. 整合容錯與可靠度設計**：
    - 超時重試政策（Client Timeout、指數退避重試 Exponential Backoff with Jitter）。
    - 幂等性機制（`X-Idempotency-Key` 處理流程、防重複提交與 Redis TTL 規劃）。

---

### 7. `07_BDD_FEATURES.md`
- 原樣複製 `e2e/biz-bdd/features/*.feature` 至 `sa_specs/biz-bdd/`。
- HTML 展開各 Feature 的情境、Background、Given／When／Then、資料表與案例識別碼，並提供下載與搜尋。
- 不修改原有標籤、情境步驟或案例語意，不將業務描述標示為執行通過紀錄。

---

### 8. `08_UAT_AND_VERIFICATION.md`
- **主要讀者**：QA 測試工程師、驗收負責人
- **1. 關鍵 UAT 驗收測試矩陣（Given-When-Then BDD 格式）**：
    - 挑選專案中 3~5 個最核心的業務邏輯與邊界防呆情境。
    - 列出測試案例 ID、測試目的、前置條件 (Given)、操作動作 (When)、預期結果 (Then)。
- **2. 執行方式與驗證紀錄**：
    - 列出驗收環境、資料準備、操作與結果紀錄要求；不列測試原始碼路徑。
    - 區分預期結果、已執行檢查及尚未執行的驗收，避免將規格當成通過證明。
    - 排障步驟連結至 `09_TROUBLESHOOTING_RUNBOOK.md`。

---

### 9. `09_TROUBLESHOOTING_RUNBOOK.md`
- **主要讀者**：系統維運 SRE、產線值班人員 / 大夜班 IT
- 開頭提供 Mermaid 故障分類圖與共用唯讀診斷入口。
- **5 分鐘緊急故障排除手冊（Troubleshooting Runbook）**：
    - 針對 2~3 個最可能發生的嚴重異常（如 DB 連線池耗盡、外部 API 斷線逾時、重複扣料衝突）。
    - 每個情境必須包含：
        - **症狀與警報（Symptoms & Alert）**
        - **快速診斷指令/日誌關鍵字（Diagnostic Query / Log Regex）**
        - **5 分鐘緊急降級與復原 SOP（Recovery Action Steps）**
        - **復原驗證與升級處理條件**；業務驗收引用 `08_UAT_AND_VERIFICATION.md`。

---

## 執行動作
請立刻掃描專案代碼，提取真實的業務邏輯與模型，在 `sa_specs/` 下生成上述 10 個標準檔案。完成前檢查十檔清單、相對連結與章節錨點、Mermaid 語法、OpenAPI 結構及資料字典欄位覆蓋，並回報實際執行的檢查與未驗證項目。

## 文件呈現與同步要求

- 以系統分析師文件呈現，先確認程式事實，但不在交付文件列原始碼依據、類別路徑或檔案追溯表。
- 文件順序：00 專案導覽、01 業務範疇、02 BPMN、03 資料模型、04 資料字典、05 API、06 整合時序、07 BDD Feature、08 UAT、09 排障。
- 專案導覽為單欄簡介與文件目錄，不加入統計、宣傳卡片、閱讀時間或重複導讀。
- 章節使用 `1`、`1-1`、`1-1-1` 層級編號。文字直接說明業務規則，刪除文件生成過程的自述。
- BPMN 保留六張圖：履約、配貨、倉內出庫、取消協調、收貨、取消後庫存釋放。不另列「WMS 取消判斷」分圖；取消規則保留於流程規格。模型、SVG 與 PNG 均為白底黑線。
- 資料字典每個實體欄位及 View 欄位必須有中文說明。索引表格的表頭與資料列之間不得留空行。
- API 說明目前需求以新增、查詢與取消為主，使用 GET／POST，未使用 PUT、PATCH 或 DELETE。不可因此新增不存在的端點。
- HTML 位於 `sa_specs/pages/`，以 Markdown／OpenAPI 為內容來源；同步更新內容與章節順序。採簡潔 Bootstrap 文件版型、淺灰側欄與背景、白色閱讀區及易讀字級，保留手機導覽、表格橫向捲動及圖片縮放。

- 泳道摘要由上而下排列人員、介面、核心服務與資料／訊息基礎設施；上游資料與資料庫不放在同一泳道。
- 現有 BPMN 的業務例外使用條件閘道；Boundary Event 並非先前已存在的圖示。未定義活動中斷、錯誤事件或等待期限前，不新增虛構的 Error／Timer Boundary Event。
