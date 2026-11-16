const token = process.env.CLOUDFLARE_API_TOKEN;
if (!token || /\s/.test(token)) {
  console.error('缺少有效的 CLOUDFLARE_API_TOKEN 環境變數，或內容含空白／換行。請在執行 Terraform 的同一個終端機輸入並 export API Token；不會自動讀取 .env。');
  process.exit(1);
}
const args = process.argv.slice(2);
if (args.length !== 0 && !(args.length === 3 && args[0] === '--remote' && args[1] === '--account-id' && /^[0-9a-f]{32}$/.test(args[2]))) {
  console.error('Usage: npm run auth:check -- [--remote --account-id <32-character-account-id>]');
  process.exit(1);
}
console.log('CLOUDFLARE_API_TOKEN 已傳入目前程序（未顯示內容）。');
const legacyVariables = ['CLOUDFLARE_API_KEY', 'CLOUDFLARE_EMAIL', 'CLOUDFLARE_API_USER_SERVICE_KEY']
  .filter(name => process.env[name]);
if (legacyVariables.length) {
  console.warn(`另有認證環境變數：${legacyVariables.join(', ')}；使用 API Token 時請清除舊認證設定後再執行 Terraform。`);
}
if (!args.length) {
  console.log('僅檢查本機環境；未驗證雲端權限。');
} else {
  try {
    const response = await fetch(`https://api.cloudflare.com/client/v4/accounts/${args[2]}/pages/projects?per_page=1`, {
      method: 'GET',
      headers: { Authorization: `Bearer ${token}` },
      redirect: 'error',
      signal: AbortSignal.timeout(15000),
    });
    const body = await response.json();
    // Report only status and numeric codes; never print headers, project data or raw responses.
    const codes = (Array.isArray(body.errors) ? body.errors : [])
      .map(error => error.code).filter(Number.isInteger);
    console.log(`Pages 唯讀診斷：HTTP ${response.status}；錯誤碼：${codes.join(', ') || '無'}`);
    if (!response.ok || body.success !== true) {
      console.error('無法讀取指定帳號的 Pages。請檢查 Token 是否有效、Account ID／授權範圍、Pages 權限、到期時間及來源 IP 限制。');
      process.exitCode = 1;
    } else {
      console.log('指定帳號的 Pages 讀取成功；建立專案仍需 Cloudflare Pages → Edit，唯讀成功不代表已有寫入權限。');
    }
  } catch {
    console.error('無法完成 Cloudflare 唯讀診斷（網路、逾時或回應格式問題）；未輸出原始錯誤或憑證。');
    process.exitCode = 1;
  }
}
