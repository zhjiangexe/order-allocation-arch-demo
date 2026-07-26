import type { ReactNode } from 'react';

import type { AsyncState } from '../hooks/useAsyncAction';
import styles from './ActionState.module.css';

interface ActionStateProps<T> {
  state: AsyncState<T>;
  /** 只有 success 才會被呼叫，因此失敗時不可能還渲染出上一次的成功輸出。 */
  children: (data: T) => ReactNode;
  idle?: ReactNode;
  pendingLabel?: string;
}

/**
 * 把「進行中」與「失敗」的呈現收在同一個地方，成功才把資料交給 children。
 *
 * 這是規格「每個動作就地呈現自身失敗」的結構性保證：呼叫端拿不到 data 就渲染不出東西，
 * 不會有人不小心漏掉錯誤分支。刻意不做全域錯誤橫幅——那會讓人看不出是哪個動作失敗。
 */
export function ActionState<T>({
  state,
  children,
  idle = null,
  pendingLabel = '處理中…',
}: ActionStateProps<T>) {
  switch (state.status) {
    case 'idle':
      return <>{idle}</>;
    case 'pending':
      return <p className={styles.pending}>{pendingLabel}</p>;
    case 'failure':
      return <p className={styles.failure} role="alert">{state.message}</p>;
    case 'success':
      return <>{children(state.data)}</>;
  }
}
