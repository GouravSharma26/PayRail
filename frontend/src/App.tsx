import React, { useState } from 'react';
import { Layout, ActiveTab } from './components/Layout';
import { OverviewPage } from './pages/OverviewPage';
import { AccountsPage } from './pages/AccountsPage';
import { TransfersPage } from './pages/TransfersPage';
import { ReconciliationPage } from './pages/ReconciliationPage';
import { OperationsPage } from './pages/OperationsPage';

export const App: React.FC = () => {
  const [activeTab, setActiveTab] = useState<ActiveTab>('overview');
  const [selectedTransferId, setSelectedTransferId] = useState<string | null>(null);

  const handleNavigateToTransfers = (transferId?: string) => {
    if (transferId) {
      setSelectedTransferId(transferId);
    }
    setActiveTab('transfers');
  };

  const handleNavigateToReconciliation = () => {
    setActiveTab('reconciliation');
  };

  const handleTabChange = (tab: ActiveTab) => {
    setActiveTab(tab);
    if (tab !== 'transfers') {
      setSelectedTransferId(null);
    }
  };

  return (
    <Layout activeTab={activeTab} onTabChange={handleTabChange}>
      {activeTab === 'overview' && (
        <OverviewPage
          onNavigateToTransfers={handleNavigateToTransfers}
          onNavigateToReconciliation={handleNavigateToReconciliation}
        />
      )}
      {activeTab === 'accounts' && (
        <AccountsPage onInspectTransfer={handleNavigateToTransfers} />
      )}
      {activeTab === 'transfers' && (
        <TransfersPage
          initialTransferId={selectedTransferId}
          onClearInitialTransferId={() => setSelectedTransferId(null)}
        />
      )}
      {activeTab === 'reconciliation' && (
        <ReconciliationPage onInspectTransfer={handleNavigateToTransfers} />
      )}
      {activeTab === 'operations' && <OperationsPage />}
    </Layout>
  );
};

export default App;
