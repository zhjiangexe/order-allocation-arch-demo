import { useEffect, useId, useRef } from 'react';
import type { ReactNode } from 'react';
import styles from './OrderDialog.module.css';

export function OrderDialog({ open, pending, blocked, formId, onClose, children }: {
  open: boolean; pending: boolean; blocked: boolean; formId: string; onClose: () => void; children: ReactNode;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  useEffect(() => {
    if (!open) return;
    const element = dialog.current!;
    const previous = document.activeElement;
    const overflow = document.body.style.overflow;
    element.showModal();
    document.body.style.overflow = 'hidden';
    return () => {
      element.close();
      document.body.style.overflow = overflow;
      if (previous instanceof HTMLElement && previous.isConnected) previous.focus();
    };
  }, [open]);
  return <dialog ref={dialog} className={styles.dialog} aria-labelledby={titleId}
    onCancel={event => { event.preventDefault(); if (!pending) onClose(); }}>
    <header className={styles.header}><h2 id={titleId}>新建訂單</h2></header>
    <div className={styles.content}>{children}</div>
    <footer className={styles.footer}>
      <button type="button" disabled={pending} onClick={onClose}>取消</button>
      <button type="submit" form={formId} disabled={pending || blocked} className={styles.submit}>
        {pending ? '送出中…' : '送出'}
      </button>
    </footer>
  </dialog>;
}
