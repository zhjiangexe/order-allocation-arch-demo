import { useId, useState } from 'react';

import type { PlaceOrderCommand } from '../api/types';
import styles from './PlaceOrderForm.module.css';

interface PlaceOrderFormProps {
  onSubmit: (command: PlaceOrderCommand) => void;
  pending: boolean;
}

/**
 * 送出前先擋掉非正整數數量與空 SKU，這種輸入不發出請求——讓後端回 400 也行，但那會
 * 在操作台上製造一次沒有必要的往返，也讓「這是輸入錯誤」的回饋慢一拍。
 */
export function PlaceOrderForm({ onSubmit, pending }: PlaceOrderFormProps) {
  const skuId = useId();
  const quantityId = useId();
  const [sku, setSku] = useState('');
  const [quantity, setQuantity] = useState('1');
  const [invalidReason, setInvalidReason] = useState<string | null>(null);

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    const reason = validate(sku, quantity);
    setInvalidReason(reason);
    if (reason === null) {
      onSubmit({ sku: sku.trim(), quantity: Number(quantity) });
    }
  }

  return (
    <form className={styles.form} onSubmit={handleSubmit} noValidate>
      <div className={styles.field}>
        <label className={styles.label} htmlFor={skuId}>SKU</label>
        <input
          id={skuId}
          className={styles.input}
          value={sku}
          onChange={(event) => setSku(event.target.value)}
          placeholder="HOT-SKU"
        />
      </div>
      <div className={styles.field}>
        <label className={styles.label} htmlFor={quantityId}>數量</label>
        <input
          id={quantityId}
          className={`${styles.input} ${styles.quantity}`}
          value={quantity}
          onChange={(event) => setQuantity(event.target.value)}
          inputMode="numeric"
        />
      </div>
      <button type="submit" disabled={pending}>
        {pending ? '送出中…' : '送出訂單'}
      </button>
      {invalidReason === null ? null : (
        <p className={styles.error} role="alert">{invalidReason}</p>
      )}
    </form>
  );
}

function validate(sku: string, quantity: string): string | null {
  if (sku.trim() === '') {
    return 'SKU 不可為空';
  }
  const parsed = Number(quantity);
  if (quantity.trim() === '' || !Number.isInteger(parsed) || parsed <= 0) {
    return '數量必須是正整數';
  }
  return null;
}
