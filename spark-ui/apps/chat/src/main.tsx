import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './app/App';
import './app/styles/global.css';

const container = document.getElementById('root');
if (!container) {
  throw new Error('[bootstrap] #root container not found');
}

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
