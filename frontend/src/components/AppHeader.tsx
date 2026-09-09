import { Select, SelectOption } from './Select';
import { useOwnerSession } from '../owner/OwnerSession';
import { NavLink, useLocation, useNavigate } from 'react-router';

import styles from './AppHeader.module.css';

export function AppHeader() {
  const session = useOwnerSession();
  const location = useLocation();
  const navigate = useNavigate();
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
      <label className={styles.owner}>貨主
        <Select aria-label="目前貨主" value={session?.owner?.ownerId ?? ''}
          disabled={!session?.owners.length || session.loading}
          onValueChange={ownerId => {
            if (ownerId !== session?.owner?.ownerId)
              void navigate(location.pathname, { state: { selectedOwnerId: ownerId } });
          }}>
          {!session?.owners.length ? <SelectOption value="">{session?.loading ? '載入中…' : '沒有貨主'}</SelectOption> : null}
          {session?.owners.map(owner => <SelectOption key={owner.ownerId} value={owner.ownerId}>{owner.name}（{owner.code}）</SelectOption>)}
        </Select>
      </label>
    </header>
  );
}

function linkClass({ isActive }: { isActive: boolean }) {
  return isActive ? `${styles.link} ${styles.active}` : styles.link;
}
