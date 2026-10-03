import { useEffect, useState } from 'react';

import { getDeadLetter, listDeadLetters } from '../api/deadLetters';
import type { DeadLetterDetail, DeadLetterPage, DeadLetterSummary } from '../api/deadLetters';
import styles from './DeadLettersPage.module.css';

const formatTime = (value: string | null) => value ? new Date(value).toLocaleString('zh-TW') : '—';

function formatPayload(payload: string | null): string {
  if (payload === null) return 'null';
  try {
    return JSON.stringify(JSON.parse(payload), null, 2);
  } catch {
    return payload;
  }
}

export function DeadLettersPage() {
  const [topic, setTopic] = useState('');
  const [groupInput, setGroupInput] = useState('');
  const [consumerGroup, setConsumerGroup] = useState('');
  const [page, setPage] = useState(0);
  const [revision, setRevision] = useState(0);
  const [data, setData] = useState<DeadLetterPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<DeadLetterDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    void listDeadLetters({ topic, consumerGroup, page }, controller.signal).then(result => {
      if (!controller.signal.aborted) setData(result);
    }).catch((cause: unknown) => {
      if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : String(cause));
    }).finally(() => {
      if (!controller.signal.aborted) setLoading(false);
    });
    return () => controller.abort();
  }, [topic, consumerGroup, page, revision]);

  async function showDetail(record: DeadLetterSummary) {
    setSelected(null);
    setDetailLoading(true);
    setDetailError(null);
    try {
      setSelected(await getDeadLetter(record));
    } catch (cause) {
      setDetailError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setDetailLoading(false);
    }
  }

  return <div className={styles.page}>
    <header className={styles.heading}>
      <div><h2>失敗訊息（DLT）</h2><p>集中檢視各個 <code>-dlt</code> topic；此頁僅供查詢。</p></div>
      <button onClick={() => setRevision(value => value + 1)} disabled={loading}>重新整理</button>
    </header>
    <form className={styles.filters} onSubmit={event => {
      event.preventDefault();
      setConsumerGroup(groupInput.trim());
      setPage(0);
    }}>
      <label>原 topic
        <select value={topic} onChange={event => { setTopic(event.target.value); setPage(0); }}>
          <option value="">全部</option>
          {data?.sourceTopics.map(value => <option key={value} value={value}>{value}</option>)}
        </select>
      </label>
      <label>Consumer group
        <input value={groupInput} onChange={event => setGroupInput(event.target.value)} placeholder="完整 group ID" />
      </label>
      <button type="submit">套用篩選</button>
    </form>
    {loading ? <p role="status">載入 DLT 中…</p> : null}
    {error ? <p role="alert">查詢失敗：{error}</p> : null}
    {data?.missingTopics.length ? <p role="alert">找不到 DLT：{data.missingTopics.join('、')}</p> : null}
    {data?.truncated ? <p>僅顯示每個 partition 最近 200 筆訊息；更舊的訊息請到 Kafbat UI 查閱。</p> : null}
    {!loading && !error && data?.items.length === 0 ? <p>目前沒有符合條件的 DLT 訊息。</p> : null}
    {data?.items.length ? <div className={styles.tableWrap}><table>
      <thead><tr><th>時間</th><th>原 topic</th><th>Consumer group</th><th>錯誤</th><th>DLT 位置</th><th></th></tr></thead>
      <tbody>{data.items.map(record => <tr key={`${record.topic}:${record.partition}:${record.offset}`}>
        <td>{formatTime(record.timestamp)}</td>
        <td>{record.originalTopic ?? '未知'}</td>
        <td>{record.consumerGroupId ?? '未知'}</td>
        <td>{record.exceptionMessage ?? record.exceptionClass ?? '無錯誤資訊'}</td>
        <td>{record.topic} · {record.partition}:{record.offset}</td>
        <td><button onClick={() => void showDetail(record)}>詳情</button></td>
      </tr>)}</tbody>
    </table></div> : null}
    <div className={styles.paging}>
      <button disabled={page === 0 || loading} onClick={() => setPage(value => value - 1)}>上一頁</button>
      <span>第 {page + 1} 頁</span>
      <button disabled={!data?.hasMore || loading} onClick={() => setPage(value => value + 1)}>下一頁</button>
    </div>
    {detailLoading ? <p role="status">載入詳情中…</p> : null}
    {detailError ? <p role="alert">詳情載入失敗：{detailError}</p> : null}
    {selected ? <section className={styles.detail} aria-label="DLT 詳情">
      <div className={styles.heading}><h3>訊息詳情</h3><button onClick={() => setSelected(null)}>關閉</button></div>
      <dl>
        <dt>原 topic</dt><dd>{selected.record.originalTopic ?? '未知'}</dd>
        <dt>Subscriber</dt><dd>{selected.record.subscriberId ?? '未知'}</dd>
        <dt>Consumer group</dt><dd>{selected.record.consumerGroupId ?? '未知'}</dd>
        <dt>Key</dt><dd>{selected.record.key ?? '—'}</dd>
        <dt>錯誤類型</dt><dd>{selected.record.exceptionClass ?? '未知'}</dd>
        <dt>錯誤訊息</dt><dd>{selected.record.exceptionMessage ?? '無'}</dd>
      </dl>
      <h4>Payload</h4><pre>{formatPayload(selected.payload)}</pre>
    </section> : null}
  </div>;
}
