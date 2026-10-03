import { afterEach, expect, it, vi } from 'vitest';

import { getDeadLetter, listDeadLetters } from './deadLetters';

afterEach(() => vi.unstubAllGlobals());

it('encodes topic, consumer group and page in the read-only query', async () => {
  const fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ items: [] }) });
  vi.stubGlobal('fetch', fetch);

  await listDeadLetters({ topic: 'ordering.order-events', consumerGroup: 'group / a', page: 2 });

  const url = String(fetch.mock.calls[0]![0]);
  expect(url).toContain('/api/demo/dead-letters?');
  expect(new URL(url, 'http://localhost').searchParams.get('topic')).toBe('ordering.order-events');
  expect(new URL(url, 'http://localhost').searchParams.get('consumerGroup')).toBe('group / a');
  expect(new URL(url, 'http://localhost').searchParams.get('page')).toBe('2');
});

it('fetches one DLT location without a write request', async () => {
  const fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ record: {}, payload: '{}' }) });
  vi.stubGlobal('fetch', fetch);

  await getDeadLetter({ topic: 'ordering.order-events-dlt', partition: 1, offset: 42 } as Parameters<typeof getDeadLetter>[0]);

  expect(fetch).toHaveBeenCalledWith('/api/demo/dead-letters/ordering.order-events-dlt/1/42', {});
});
