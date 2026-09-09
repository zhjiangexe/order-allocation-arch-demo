import { NavLink } from 'react-router';

import styles from './AppHeader.module.css';

export function AppHeader() {
  return (
    <header className={styles.header}>
      <h1 className={styles.title}>ALLOCATION!</h1>
      <nav className={styles.nav}>
        <NavLink to="/orders" className={linkClass}>
          訂單
        </NavLink>
        <NavLink to="/allocations" className={linkClass}>
          配貨佇列
        </NavLink>
        <NavLink to="/stock" className={linkClass}>
          庫存
        </NavLink>
        <NavLink to="/catalog" className={linkClass}>
          主檔瀏覽
        </NavLink>
      </nav>
    </header>
  );
}

function linkClass({ isActive }: { isActive: boolean }) {
  return isActive ? `${styles.link} ${styles.active}` : styles.link;
}
