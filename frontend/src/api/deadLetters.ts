export interface DeadLetterSummary {
  topic: string;
  partition: number;
  offset: number;
  timestampMillis: number;
  timestamp: string | null;
  key: string | null;
  originalTopic: string | null;
  subscriberId: string | null;
  consumerGroupId: string | null;
  exceptionClass: string | null;
  exceptionMessage: string | null;
}

export interface DeadLetterPage {
  items: DeadLetterSummary[];
  page: number;
  limit: number;
  hasMore: boolean;
  truncated: boolean;
  missingTopics: string[];
  sourceTopics: string[];
}

export interface DeadLetterDetail {
  record: DeadLetterSummary;
  payload: string | null;
}

async function read<T>(path: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(`/api/demo/dead-letters${path}`, signal ? { signal } : {});
  if (!response.ok) {
    const body = (await response.text()).trim();
    throw new Error(body || `HTTP ${response.status}`);
  }
  return response.json() as Promise<T>;
}

export function listDeadLetters(
  filters: { topic: string; consumerGroup: string; page: number }, signal?: AbortSignal,
): Promise<DeadLetterPage> {
  const params = new URLSearchParams({ page: String(filters.page), limit: '50' });
  if (filters.topic) params.set('topic', filters.topic);
  if (filters.consumerGroup) params.set('consumerGroup', filters.consumerGroup);
  return read<DeadLetterPage>(`?${params}`, signal);
}

export function getDeadLetter(record: DeadLetterSummary, signal?: AbortSignal): Promise<DeadLetterDetail> {
  return read<DeadLetterDetail>(
    `/${encodeURIComponent(record.topic)}/${record.partition}/${record.offset}`, signal,
  );
}
