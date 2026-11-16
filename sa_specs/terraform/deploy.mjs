import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const directory = path.dirname(fileURLToPath(import.meta.url));
const pages = path.resolve(directory, '../pages');
const args = process.argv.slice(2);
if (args.some(arg => arg !== '--dry-run')) {
  console.error('Usage: npm run deploy -- [--dry-run]');
  process.exit(1);
}
const dryRun = args.includes('--dry-run');

function run(command, arguments_, options = {}) {
  const result = spawnSync(command, arguments_, {
    cwd: directory,
    stdio: 'inherit',
    ...options,
  });
  if (result.error || result.status !== 0) {
    console.error(result.error?.message ?? `${command} 執行失敗（${result.status ?? result.signal}）。`);
    process.exit(result.status || 1);
  }
  return result;
}

// Use applied Terraform outputs so project and account cannot drift from tfvars.
const result = run('terraform', ['output', '-json', 'deployment'], {
  encoding: 'utf8', stdio: ['ignore', 'pipe', 'inherit'],
});
const target = JSON.parse(result.stdout);
if (!/^[0-9a-f]{32}$/.test(target.account_id ?? '')
    || !/^[a-z0-9][a-z0-9-]*$/.test(target.project_name ?? '')
    || !/^[A-Za-z0-9][A-Za-z0-9._/-]*$/.test(target.production_branch ?? '')) {
  throw new Error('Terraform deployment output 不完整；請先完成 terraform apply。');
}
if (!dryRun) {
  run(process.execPath, [path.join(directory, 'check-auth.mjs')]);
}

run('npm', ['run', 'build'], { cwd: pages });
run('npm', ['run', 'check'], { cwd: pages });

const wrangler = path.join(directory, 'node_modules/wrangler/bin/wrangler.js');
const deployArgs = [
  wrangler, 'pages', 'deploy', path.join(pages, 'dist'),
  '--project-name', target.project_name,
  '--branch', target.production_branch,
];
console.log(`正式部署目標：${target.project_name} / ${target.production_branch}`);
if (dryRun) {
  console.log('建置與內容檢查完成；dry-run 不會上傳或修改 Cloudflare。');
} else {
  run(process.execPath, deployArgs, {
    env: {
      ...process.env,
      CLOUDFLARE_ACCOUNT_ID: target.account_id,
      WRANGLER_SEND_METRICS: 'false',
      CI: 'true',
    },
  });
}
