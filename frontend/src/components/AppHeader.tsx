import { NavLink } from 'react-router';

import type { DemoConfig } from '../api/types';
import type { AsyncState } from '../hooks/useAsyncAction';
import styles from './AppHeader.module.css';

interface AppHeaderProps {
  config: AsyncState<DemoConfig>;
}

/**
 * 顯示目前生效的分區策略：`sku` 是 v3（同一 SKU 的下單事件收斂到單一 partition，
 * allocation consumer 成為該 SKU 的 single writer），其餘值是 v1。
 *
 * 這個值在後端啟動時解析，執行期改不了，所以這裡只揭露、不提供切換。
 */
export function AppHeader({ config }: AppHeaderProps) {
  return (
    <header className={styles.header}>
      <h1 className={styles.title}>order-promising 操作台</h1>
      <nav className={styles.nav}>
        <NavLink to="/orders" className={linkClass}>訂單</NavLink>
        <NavLink to="/stock" className={linkClass}>庫存</NavLink>
      </nav>
      <p className={styles.strategy}>{describe(config)}</p>
    </header>
  );
}

function linkClass({ isActive }: { isActive: boolean }) {
  return isActive ? `${styles.link} ${styles.active}` : styles.link;
}

function describe(config: AsyncState<DemoConfig>) {
  switch (config.status) {
    case 'idle':
    case 'pending':
      return '分區策略讀取中…';
    case 'failure':
      return '分區策略讀取失敗：' + config.message;
    case 'success':
      return (
        <>
          分區策略{' '}
          <span className={styles.strategyValue}>{config.data.partitionKeyStrategy}</span>
          {config.data.partitionKeyStrategy === 'sku'
            ? '（v3 single-writer）'
            : '（v1 樂觀鎖）'}
        </>
      );
  }
}
