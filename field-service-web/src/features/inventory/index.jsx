/**
 * Inventory surface — route group for /inventory/*.
 *
 * Lazy-loaded entry chunk for the Inventory section.
 * Accessible to DISPATCHER, MANAGER, ADMIN (full view), and TECHNICIAN
 * (own-vehicle location only — enforced server-side).
 *
 * Routes:
 *   /inventory            → StockPositionsPage
 *   /inventory/alerts     → LowStockPage
 */

import React from 'react';
import { Routes, Route, NavLink } from 'react-router-dom';

import { StockPositionsPage } from './StockPositionsPage.jsx';
import { LowStockPage } from './LowStockPage.jsx';

import styles from './InventorySurface.module.css';

/**
 * @param {{ roles?: string[] }} props
 */
export default function InventorySurface({ roles = [] }) {
  return (
    <div className={styles.surface}>
      <nav className={styles.subnav} aria-label="Inventory navigation">
        <NavLink
          to="/inventory"
          end
          className={({ isActive }) =>
            [styles.navLink, isActive ? styles.navLinkActive : ''].filter(Boolean).join(' ')
          }
        >
          Stock Positions
        </NavLink>
        <NavLink
          to="/inventory/alerts"
          className={({ isActive }) =>
            [styles.navLink, isActive ? styles.navLinkActive : ''].filter(Boolean).join(' ')
          }
        >
          Low Stock Alerts
        </NavLink>
      </nav>

      <Routes>
        <Route index element={<StockPositionsPage roles={roles} />} />
        <Route path="alerts" element={<LowStockPage roles={roles} />} />
      </Routes>
    </div>
  );
}
