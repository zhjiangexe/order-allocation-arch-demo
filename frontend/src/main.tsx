import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter, RouterProvider } from 'react-router';

import { App } from './App';
import './styles.css';

const container = document.getElementById('root');
if (container === null) {
  throw new Error('Missing #root element');
}

const router = createBrowserRouter([{ path: '*', element: <App /> }]);

createRoot(container).render(
  <StrictMode>
    <RouterProvider router={router} />
  </StrictMode>,
);
