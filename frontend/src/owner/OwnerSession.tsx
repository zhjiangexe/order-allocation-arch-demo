import { createContext, useContext, useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { useLocation } from 'react-router';
import { listOwners } from '../api/client';
import type { OwnerView } from '../api/types';

const STORAGE_KEY = 'allocation.ownerId';
export interface OwnerSession {
  owners: OwnerView[];
  owner: OwnerView | null;
  loading: boolean;
  error: string | null;
  retry: () => void;
}
export const OwnerSessionContext = createContext<OwnerSession | null>(null);
export const useOwnerSession = () => useContext(OwnerSessionContext);

function savedOwner() {
  try { return sessionStorage.getItem(STORAGE_KEY); } catch { return null; }
}

/** 選擇模擬登入貨主；切換透過 router 完成，尊重收貨頁的離頁阻擋。 */
export function OwnerSessionProvider({ children }: { children: ReactNode }) {
  const location = useLocation();
  const [remembered, setRemembered] = useState(savedOwner);
  const [owners, setOwners] = useState<OwnerView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    let cancelled = false;
    setLoading(true); setError(null);
    void listOwners().then(data => { if (!cancelled) setOwners(data); })
      .catch((cause: unknown) => { if (!cancelled) setError(cause instanceof Error ? cause.message : String(cause)); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [revision]);
  const requested: unknown = location.state?.selectedOwnerId;
  const owner = owners.find(candidate => candidate.ownerId === (typeof requested === 'string' ? requested : remembered))
    ?? owners[0] ?? null;
  useEffect(() => {
    if (!owner) return;
    setRemembered(owner.ownerId);
    try { sessionStorage.setItem(STORAGE_KEY, owner.ownerId); } catch { /* 本次操作仍可使用，不強制要求儲存權限。 */ }
  }, [owner]);
  return <OwnerSessionContext.Provider value={{ owners, owner, loading, error, retry: () => setRevision(value => value + 1) }}>
    {children}
  </OwnerSessionContext.Provider>;
}
