import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import * as api from '../api/deadLetters';
import type { DeadLetterPage } from '../api/deadLetters';
import { DeadLettersPage } from './DeadLettersPage';

const record = {
  topic: 'ordering.order-events-dlt', partition: 0, offset: 4, timestampMillis: 1,
  timestamp: '2026-10-01T00:00:00Z', key: 'order-1', originalTopic: 'ordering.order-events',
  subscriberId: 'allocation', consumerGroupId: 'allocation-group',
  exceptionClass: 'java.lang.IllegalArgumentException', exceptionMessage: 'invalid contract',
};

const response = (overrides: Partial<DeadLetterPage> = {}): DeadLetterPage => ({
  items: [record], page: 0, limit: 50, hasMore: false, truncated: false,
  missingTopics: [], sourceTopics: ['ordering.order-events', 'inventory.stock-events'], ...overrides,
});

beforeEach(() => {
  vi.spyOn(api, 'listDeadLetters').mockResolvedValue(response());
  vi.spyOn(api, 'getDeadLetter').mockResolvedValue({ record, payload: '{"eventId":"one"}' });
});
afterEach(() => vi.restoreAllMocks());

it('lists DLT records and loads a selected payload', async () => {
  render(<DeadLettersPage />);
  expect(await screen.findByText('invalid contract')).toBeVisible();
  expect(screen.getByText('allocation-group')).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: '詳情' }));
  const detail = await screen.findByRole('region', { name: 'DLT 詳情' });
  expect(detail.querySelector('pre')?.textContent).toBe('{\n  "eventId": "one"\n}');
  expect(api.getDeadLetter).toHaveBeenCalledWith(record);
});

it('keeps a non-JSON payload unchanged', async () => {
  vi.mocked(api.getDeadLetter).mockResolvedValue({ record, payload: 'plain text' });
  render(<DeadLettersPage />);
  await screen.findByText('invalid contract');
  fireEvent.click(screen.getByRole('button', { name: '詳情' }));
  const detail = await screen.findByRole('region', { name: 'DLT 詳情' });
  expect(detail.querySelector('pre')?.textContent).toBe('plain text');
});

it('applies topic and consumer group filters and changes page', async () => {
  vi.mocked(api.listDeadLetters).mockResolvedValue(response({ hasMore: true }));
  render(<DeadLettersPage />);
  await screen.findByText('invalid contract');
  fireEvent.change(screen.getByLabelText('原 topic'), { target: { value: 'inventory.stock-events' } });
  await waitFor(() => expect(api.listDeadLetters).toHaveBeenCalledWith(
    { topic: 'inventory.stock-events', consumerGroup: '', page: 0 }, expect.any(AbortSignal),
  ));
  fireEvent.change(screen.getByLabelText('Consumer group'), { target: { value: 'allocation-group' } });
  fireEvent.click(screen.getByRole('button', { name: '套用篩選' }));
  await waitFor(() => expect(api.listDeadLetters).toHaveBeenCalledWith(
    { topic: 'inventory.stock-events', consumerGroup: 'allocation-group', page: 0 }, expect.any(AbortSignal),
  ));
  fireEvent.click(screen.getByRole('button', { name: '下一頁' }));
  await waitFor(() => expect(api.listDeadLetters).toHaveBeenCalledWith(
    { topic: 'inventory.stock-events', consumerGroup: 'allocation-group', page: 1 }, expect.any(AbortSignal),
  ));
});

it('shows empty results, missing topics and query failures', async () => {
  vi.mocked(api.listDeadLetters).mockResolvedValueOnce(response({ items: [], missingTopics: ['inventory.stock-events-dlt'] }))
    .mockRejectedValueOnce(new Error('Kafka unavailable'));
  render(<DeadLettersPage />);
  expect(await screen.findByText('目前沒有符合條件的 DLT 訊息。')).toBeVisible();
  expect(screen.getByRole('alert')).toHaveTextContent('inventory.stock-events-dlt');
  fireEvent.click(screen.getByRole('button', { name: '重新整理' }));
  expect(await screen.findByText('查詢失敗：Kafka unavailable')).toBeVisible();
});
