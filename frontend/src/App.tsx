import { Navigate, Route, Routes } from 'react-router';

import { OwnerSessionProvider, useOwnerSession } from './owner/OwnerSession';
import { AppHeader } from './components/AppHeader';
import { AllocationsPage } from './pages/AllocationsPage';
import { CatalogPage } from './pages/CatalogPage';
import { OrdersPage } from './pages/OrdersPage';
import { StockPage } from './pages/StockPage';

/**
 * 訂單、配貨佇列、庫存與主檔瀏覽；詳情僅追蹤目前選取的一筆訂單。
 */
export function App() {
  return <OwnerSessionProvider><Workspace /></OwnerSessionProvider>;
}

function Workspace() {
  const session = useOwnerSession()!;
  return (
    <>
      <AppHeader />
      <main key={session.owner?.ownerId ?? 'no-owner'}>
        {session.loading ? <p>載入貨主中…</p> : session.error ? <p role="alert">貨主載入失敗：{session.error} <button onClick={session.retry}>重試</button></p>
          : !session.owner ? <p>目前沒有可用的貨主。</p> : <Routes>
          <Route path="/" element={<Navigate to="/orders" replace />} />
          <Route path="/orders" element={<OrdersPage />} />
          <Route path="/allocations" element={<AllocationsPage />} />
          <Route path="/stock" element={<StockPage />} />
          <Route path="/catalog" element={<CatalogPage />} />
          <Route path="*" element={<Navigate to="/orders" replace />} />
        </Routes>}
      </main>
    </>
  );
}
