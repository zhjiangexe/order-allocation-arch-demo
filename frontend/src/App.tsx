import { useEffect } from 'react';
import { Navigate, Route, Routes } from 'react-router';

import { getDemoConfig } from './api/client';
import { AppHeader } from './components/AppHeader';
import { useAsyncAction } from './hooks/useAsyncAction';
import { OrdersPage } from './pages/OrdersPage';
import { StockPage } from './pages/StockPage';

/**
 * 兩頁。訂單詳細不設第三個路由也不做 modal——訂單表示只有八個欄位，列表那一列就顯示
 * 得下，第二層視圖會是同一份資料的第二次呈現。
 */
export function App() {
  const config = useAsyncAction(getDemoConfig);
  const { run: loadConfig } = config;

  // 分區策略在後端啟動時就固定了，整個 session 不會變，所以只在這裡取一次；
  // 換頁不重取（頁首活在 router 外層，不會隨路由重新掛載）。
  useEffect(() => {
    void loadConfig();
  }, [loadConfig]);

  return (
    <>
      <AppHeader config={config.state} />
      <main>
        <Routes>
          <Route path="/" element={<Navigate to="/orders" replace />} />
          <Route path="/orders" element={<OrdersPage />} />
          <Route path="/stock" element={<StockPage />} />
          <Route path="*" element={<Navigate to="/orders" replace />} />
        </Routes>
      </main>
    </>
  );
}
