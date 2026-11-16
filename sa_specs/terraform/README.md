# Cloudflare Pages 部署

Terraform 管理 Pages 專案與選用的自訂網域／DNS；Wrangler 上傳 `../pages/dist`。預設專案名稱為 `archone-sa`、正式分支標籤為 `main`，先使用 Cloudflare 提供的 `pages.dev` 網址。

目前只有部署設定，尚未建立雲端資源或公開網站。部署完成後，HTML、圖表及文件下載均可透過公開網址存取。

採用 **Direct Upload**，不需把整個 repository 授權給 Cloudflare。之後可在 CI 執行同一套部署指令；若改用 Cloudflare 內建 Git 整合，需另建 Pages 專案。[官方 Direct Upload 說明](https://developers.cloudflare.com/pages/get-started/direct-upload/)

## 1 需要提供的資訊

| 資訊 | 用途 | 必要性 |
| --- | --- | --- |
| Cloudflare Account ID | 指定 Pages 所在帳號，32 位十六進位字串 | 必填 |
| Pages 專案名稱 | 預設 `archone-sa`，也可換成你的名稱 | 可沿用預設 |
| 自訂網域 | 例如 `sa.example.com` | 可先不填，使用 `pages.dev` |
| Cloudflare Zone ID | 讓 Terraform 管理該網域的 CNAME | 只有要管理 Cloudflare DNS 時需要 |
| API Token | 在本機環境變數設定 `CLOUDFLARE_API_TOKEN` | 執行 plan／apply／上傳時需要 |

Account ID 與 Zone ID 不同，可在 Cloudflare 控制台取得。API Token 不需要貼到對話，也不要填入 `terraform.tfvars`。設定在實際執行指令的終端機或受保護的執行環境即可；另一個終端機的環境變數不會自動傳入目前程序。

Token 權限限定至目標帳號：`Account → Cloudflare Pages → Edit`。若設定 `dns_zone_id`，再加上目標 Zone 的 `Zone → DNS → Edit`。[官方 Token 設定方式](https://developers.cloudflare.com/pages/how-to/use-direct-upload-with-continuous-integration/)

## 2 首次建立與上傳

需要 Terraform 1.9 以上（低於 2.0）、Node.js 22 以上及 npm。Provider 與 Wrangler 的解析版本分別鎖在 `.terraform.lock.hcl`、`package-lock.json`。

從 repository 根目錄執行：

```sh
cd sa_specs/terraform
cp terraform.tfvars.example terraform.tfvars
```

編輯 `terraform.tfvars` 填入 Account ID 與專案名稱。首次先保持 `custom_domain`、`dns_zone_id` 未設定，完成網站上傳後再綁定網域。

在 zsh 終端機隱藏輸入 Token，再匯出為環境變數：

```zsh
read -r -s 'CLOUDFLARE_API_TOKEN?Cloudflare API Token: '
export CLOUDFLARE_API_TOKEN
npm run auth:check
```

安裝依賴、檢查並建立專案：

```sh
npm ci
npm ci --prefix ../pages
terraform init
terraform fmt -check -recursive
terraform validate
terraform test
npm run auth:check && terraform plan -out=site.tfplan
npm run auth:check && terraform apply site.tfplan
```

`plan` 會顯示實際新增或修改的資源；`apply` 建立專案，但還沒有網站內容。上傳前可先執行 dry-run（需要已 apply 的 Terraform state）：

```sh
npm run deploy -- --dry-run
npm run deploy
terraform output -raw pages_url
```

`deploy.mjs` 讀取已套用 state 中的 Account ID、專案名稱及正式分支，執行網站 `build`、`check`，通過後才上傳。這會更新正式網站，包含目前工作目錄尚未提交的文件修改；並非只上傳 Git HEAD。`--dry-run` 只建置與檢查，不會上傳。

只會上傳 `sa_specs/pages/dist`，Terraform 設定、state、Token、node_modules 與後端原始碼不會進入部署產物。網站本身的規格、BDD、BPMN 及 OpenAPI 下載檔會隨網站公開。

## 3 自訂網域（選用）

先確認 `pages.dev` 網站可用，再設定 `terraform.tfvars`：

```hcl
custom_domain = "sa.example.com"
dns_zone_id   = "目標 Cloudflare Zone 的 32 位 ID"
```

執行新的 `terraform plan -out=site.tfplan`，檢視後 `terraform apply site.tfplan`。這會建立 Pages 網域綁定與指向實際 Pages subdomain 的 CNAME。使用的 Zone 必須包含指定網域；既有同名 DNS 記錄需要先確認是否應匯入，不能直接覆蓋其他服務。

若 DNS 放在其他供應商，只設定 `custom_domain`，保留 `dns_zone_id = null`；apply 後依 `terraform output manual_dns_record` 建立 CNAME。外部 DNS 使用子網域；根網域需由同一 Cloudflare 帳號管理。網域與 HTTPS 憑證啟用需等待驗證完成。[官方自訂網域說明](https://developers.cloudflare.com/pages/configuration/custom-domains/)

## 4 後續更新與 state

修改原文件後，在此目錄執行 `npm run deploy` 即可重新建置、驗證及上傳。只有調整專案／網域設定才需要重新 plan／apply。部署指令不會自行執行 Terraform apply。

目前使用本機 state，存放在此目錄並排除 Git；保留及備份 `terraform.tfstate`，不要刪除後重建同名專案。多機或 CI 管理 Terraform 時，先改用團隊選定、具鎖定功能的 remote backend。只在 CI 上傳網站則不必讓 CI 管理 Terraform。

如果使用既有 **Direct Upload** 專案，先設定相同名稱，再匯入：

```sh
terraform import cloudflare_pages_project.site '<ACCOUNT_ID>/<PROJECT_NAME>'
terraform plan
```

既有 Git 整合專案不適用本設定，需先調整部署方案。Pages 專案有 `prevent_destroy`，名稱變更造成的重建會被 Terraform 擋下；若確實要刪除或重建，先確認網站影響再修改此設定。

不需憑證的本機驗證：`terraform init -backend=false`、`terraform validate`、`terraform test`；測試使用 mock provider，只產生模擬 plan，不存取 Cloudflare。實際帳號權限、專案名稱可用性、DNS 與上傳結果仍需取得資訊後才能驗證。

## 5 認證錯誤 9106

`Missing X-Auth-Key, X-Auth-Email or Authorization headers` 表示 Cloudflare 收到的請求缺少認證標頭。本設定使用 API Token，Provider 會從 `CLOUDFLARE_API_TOKEN` 加入 Authorization；不需另外填入 Email 或 Global API Key。[Provider 認證設定](https://registry.terraform.io/providers/cloudflare/cloudflare/5.28.1/docs)

請在執行 Terraform 的同一個終端機重新隱藏輸入 Token 並 `export`（見第 2 節），再執行 `npm run auth:check`。只寫進 `.env`、設定成未匯出的 shell 變數，或在另一個終端機設定，都不會自動提供給 Terraform。此檢查只確認環境變數已傳入且不含空白，不會顯示 Token，也不代表 Token 的權限已通過雲端驗證。

缺少憑證時，首次建立資源的 plan 仍可能成功；apply 時才會遇到認證錯誤。補上 Token 後重新產生 plan，再 apply，以失敗後的最新 state 為準：

```sh
npm run auth:check && terraform plan -out=site.tfplan
npm run auth:check && terraform apply site.tfplan
```

若錯誤改為權限不足，檢查 Token 是否有效、是否包含目標帳號，以及 `Cloudflare Pages → Edit` 權限。不要將 Token 內容貼到日誌或對話。

## 6 認證錯誤 10000／403

此錯誤無法單憑訊息區分 Token 無效或授權不足。先檢查：

- 使用的是 API Token 的完整秘密值，沒有誤填 Token ID、Account ID 或 Global API Key。
- Token 權限包含 `Account → Cloudflare Pages → Edit`，且 Account Resources 包含 `terraform.tfvars` 中的 Account ID。只有 DNS Edit、Workers Scripts Edit 或 Pages Read 不足以建立 Pages 專案。[建立專案所需權限](https://developers.cloudflare.com/api/resources/pages/subresources/projects/methods/create/)
- Token 尚未到期、已到可使用時間，且來源 IP 符合所設限制。
- 如果重新產生 Token，已在執行 Terraform 的同一個終端機更新並 export；舊值不會自動替換。

執行唯讀診斷（將 Account ID 換成實際值）：

```sh
npm run auth:check -- --remote --account-id <ACCOUNT_ID>
```

此指令只呼叫 GET 列出 Pages 專案的 API，僅印出 HTTP 狀態與數字錯誤碼，不印出 Token 或專案內容，也不新增資源。HTTP 200 表示此帳號的 Pages 讀取成功，但不能證明具備建立專案所需的 Edit 權限；403 則仍需核對 Token、帳號與授權設定。診斷若提示舊認證環境變數，使用 API Token 時可先執行 `unset CLOUDFLARE_API_KEY CLOUDFLARE_EMAIL CLOUDFLARE_API_USER_SERVICE_KEY` 再重試。
