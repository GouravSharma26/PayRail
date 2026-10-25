import React, { useEffect, useState } from 'react';
import {
  Activity,
  ArrowLeftRight,
  CheckCircle2,
  Database,
  FileCheck2,
  HardDrive,
  LayoutDashboard,
  ShieldAlert,
  Users,
} from 'lucide-react';
import { api } from '../api/client';
import { SystemHealthSummaryResponse } from '../types/api';

export type ActiveTab = 'overview' | 'accounts' | 'transfers' | 'reconciliation' | 'operations';

interface LayoutProps {
  activeTab: ActiveTab;
  onTabChange: (tab: ActiveTab) => void;
  children: React.ReactNode;
}

export const Layout: React.FC<LayoutProps> = ({ activeTab, onTabChange, children }) => {
  const [health, setHealth] = useState<SystemHealthSummaryResponse | null>(null);

  useEffect(() => {
    let isMounted = true;
    const controller = new AbortController();

    async function loadHealth() {
      try {
        const data = await api.getHealthSummary(controller.signal);
        if (isMounted) {
          setHealth(data);
        }
      } catch {
        if (isMounted) {
          setHealth(null);
        }
      }
    }

    loadHealth();
    // Bounded periodic refresh for header health pill (15s)
    const interval = setInterval(loadHealth, 15000);

    return () => {
      isMounted = false;
      controller.abort();
      clearInterval(interval);
    };
  }, []);

  const healthStatus = health?.status || 'UNKNOWN';
  const isHealthy = healthStatus === 'UP';

  return (
    <div className="app-container">
      {/* Persistent Synthetic Simulator Warning Banner required on every route */}
      <div className="simulator-banner" role="banner" aria-label="Simulator warning">
        <ShieldAlert size={16} />
        <span>PayRail Synthetic Payment Simulator — no real money or bank connections.</span>
        <span className="simulator-banner-tag">Educational Demo</span>
      </div>

      {/* Main Header */}
      <header className="header" role="banner">
        <div className="brand">
          <div className="brand-icon" aria-hidden="true">
            <ArrowLeftRight size={20} />
          </div>
          <div>
            <h1 className="brand-title">PayRail</h1>
            <div className="brand-subtitle">Operations & Ledger Simulator</div>
          </div>
        </div>

        {/* Persistent Navigation */}
        <nav className="nav" role="navigation" aria-label="Main Navigation">
          <button
            className={`nav-link ${activeTab === 'overview' ? 'active' : ''}`}
            onClick={() => onTabChange('overview')}
            aria-current={activeTab === 'overview' ? 'page' : undefined}
          >
            <LayoutDashboard size={16} />
            <span>Overview</span>
          </button>
          <button
            className={`nav-link ${activeTab === 'accounts' ? 'active' : ''}`}
            onClick={() => onTabChange('accounts')}
            aria-current={activeTab === 'accounts' ? 'page' : undefined}
          >
            <Users size={16} />
            <span>Accounts</span>
          </button>
          <button
            className={`nav-link ${activeTab === 'transfers' ? 'active' : ''}`}
            onClick={() => onTabChange('transfers')}
            aria-current={activeTab === 'transfers' ? 'page' : undefined}
          >
            <ArrowLeftRight size={16} />
            <span>Transfers</span>
          </button>
          <button
            className={`nav-link ${activeTab === 'reconciliation' ? 'active' : ''}`}
            onClick={() => onTabChange('reconciliation')}
            aria-current={activeTab === 'reconciliation' ? 'page' : undefined}
          >
            <FileCheck2 size={16} />
            <span>Reconciliation</span>
          </button>
          <button
            className={`nav-link ${activeTab === 'operations' ? 'active' : ''}`}
            onClick={() => onTabChange('operations')}
            aria-current={activeTab === 'operations' ? 'page' : undefined}
          >
            <Activity size={16} />
            <span>Operations</span>
          </button>
        </nav>

        {/* Health status indicator */}
        <div className="header-status">
          <div
            className={`badge ${isHealthy ? 'badge-success' : 'badge-warning'}`}
            title={`Database: ${health?.dbStatus || 'UNKNOWN'} | Kafka: ${health?.kafkaStatus || 'UNKNOWN'}`}
          >
            <span className="pulse-dot" />
            <span>SYSTEM {healthStatus}</span>
          </div>
        </div>
      </header>

      {/* Main Page Content */}
      <main className="main-content" role="main">
        {children}
      </main>

      {/* Footer */}
      <footer className="footer" role="contentinfo">
        <div>
          PayRail Synthetic Payment Processing Simulator &bull; Authoritative PostgreSQL Invariants &bull; Java 21 / Spring Boot &bull; React
        </div>
        <div style={{ display: 'flex', gap: '14px', alignItems: 'center' }}>
          <span><Database size={12} style={{ verticalAlign: 'middle', marginRight: 4 }} />PostgreSQL 16 Authoritative</span>
          <span><HardDrive size={12} style={{ verticalAlign: 'middle', marginRight: 4 }} />Transactional Outbox</span>
          <span><CheckCircle2 size={12} style={{ verticalAlign: 'middle', marginRight: 4 }} />Strict Double-Entry Ledger</span>
        </div>
      </footer>
    </div>
  );
};
