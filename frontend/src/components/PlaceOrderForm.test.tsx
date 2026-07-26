import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { PlaceOrderForm } from './PlaceOrderForm';

async function submitWith(sku: string, quantity: string) {
  const onSubmit = vi.fn();
  render(<PlaceOrderForm onSubmit={onSubmit} pending={false} />);
  const user = userEvent.setup();

  await user.clear(screen.getByLabelText('SKU'));
  if (sku !== '') {
    await user.type(screen.getByLabelText('SKU'), sku);
  }
  await user.clear(screen.getByLabelText('數量'));
  if (quantity !== '') {
    await user.type(screen.getByLabelText('數量'), quantity);
  }
  await user.click(screen.getByRole('button', { name: '送出訂單' }));

  return onSubmit;
}

describe('PlaceOrderForm', () => {
  it('有效輸入會送出，數量以數字型別傳出', async () => {
    const onSubmit = await submitWith('HOT-SKU', '3');

    expect(onSubmit).toHaveBeenCalledExactlyOnceWith({ sku: 'HOT-SKU', quantity: 3 });
  });

  it.each([
    ['數量為 0', 'HOT-SKU', '0'],
    ['數量為負', 'HOT-SKU', '-3'],
    ['SKU 為空', '', '1'],
    ['數量為空', 'HOT-SKU', ''],
  ])('%s 時不送出請求', async (_case, sku, quantity) => {
    const onSubmit = await submitWith(sku, quantity);

    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });
});
