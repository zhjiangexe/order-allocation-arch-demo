import { Navigate, Route, Routes } from 'react-router';

import { AppHeader } from './components/AppHeader';
import { CatalogPage } from './pages/CatalogPage';
import { OrdersPage } from './pages/OrdersPage';
import { StockPage } from './pages/StockPage';

/**
 * 三頁。訂單目前直接在列表呈現，庫存頁處理批次收貨與查詢，Catalog 則只提供唯讀主檔瀏覽。
 */
export function App() {
  return (
    <>
      <AppHeader />
      <main>
        <Routes>
          <Route path="/" element={<Navigate to="/orders" replace />} />
          <Route path="/orders" element={<OrdersPage />} />
          <Route path="/stock" element={<StockPage />} />
          <Route path="/catalog" element={<CatalogPage />} />
          <Route path="*" element={<Navigate to="/orders" replace />} />
        </Routes>
      </main>
    </>
  );
}
