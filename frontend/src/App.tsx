import { Navigate, Route, Routes } from 'react-router';

import { AppHeader } from './components/AppHeader';
import { AllocationsPage } from './pages/AllocationsPage';
import { CatalogPage } from './pages/CatalogPage';
import { OrdersPage } from './pages/OrdersPage';
import { StockPage } from './pages/StockPage';

/**
 * 訂單、配貨佇列、庫存與主檔瀏覽；詳情僅追蹤目前選取的一筆訂單。
 */
export function App() {
  return (
    <>
      <AppHeader />
      <main>
        <Routes>
          <Route path="/" element={<Navigate to="/orders" replace />} />
          <Route path="/orders" element={<OrdersPage />} />
          <Route path="/allocations" element={<AllocationsPage />} />
          <Route path="/stock" element={<StockPage />} />
          <Route path="/catalog" element={<CatalogPage />} />
          <Route path="*" element={<Navigate to="/orders" replace />} />
        </Routes>
      </main>
    </>
  );
}
