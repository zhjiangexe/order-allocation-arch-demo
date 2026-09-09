import { Select, SelectOption } from './Select';
import { useId, useState } from 'react';

import type { StockLine } from '../api/stockLines';
import type {
  FacilityView,
  OwnerView,
  StockReceiptConfirmed,
  StockBatchView,
  StockLocationView,
} from '../api/types';
import type { AsyncState } from '../hooks/useAsyncAction';
import { ActionState } from './ActionState';
import { ConfirmStockReceiptDialog, type ConfirmStockReceiptInput } from './ConfirmStockReceiptDialog';
import styles from './StockPanel.module.css';

interface StockPanelProps {
  ownerId?: string | undefined;
  /** 已經與主檔 join 過的列表：該貨主的每一個規格各一列，這個倉沒有的四個數字都是 0。 */
  initialScope?: { ownerId: string; facilityId: string; locationId: string; sku: string } | undefined;
  receiptLocked?: boolean;
  lines: AsyncState<StockLine[]>;
  receiptConfirmation: AsyncState<StockReceiptConfirmed>;
  owners: OwnerView[];
  facilitiesOf: (ownerId: string) => readonly FacilityView[];
  locationsOf: (facilityId: string) => readonly StockLocationView[];
  onQuery: (ownerId: string, facilityId: string, locationId: string) => void;
  onConfirmReceipt: (input: ConfirmStockReceiptInput) => void;
  /** 改動貨主或設施時作廢畫面上的結果——它屬於上一個組合。 */
  onScopeChange: () => void;
}

/**
 * 庫存頁：查一個庫位替一個貨主放了什麼，並從那一列收貨。
 *
 * <p><b>查詢軸是 (貨主, 設施, 庫位)，不是 SKU。</b>Facility 決定作業政策，location 是實際
 * 庫存端點；少了後者，多庫位 Facility 就無法回答查的是哪一批貨。配貨從不
 * 跨倉——每一次配貨都鎖在一個倉裡。畫面問的問題與系統做的決策因此對不上，而且要知道一個倉
 * 有沒有東西，你得先知道有哪些 SKU、再一個一個查。
 *
 * <p><b>一列一個規格，批降為展開後的第二層。</b>四批同一個規格會佔四列，而「這個規格總共還
 * 能出幾件」正是決定要不要收貨的數字。展開這一層違反了訂單頁定下的「不做點開看詳細」，但那條
 * 規則守的是「同一份資料呈現兩次」——這裡一個規格的列真的裝不下 N 批，是兩個層級不是兩次呈現。
 *
 * <p><b>在手含過期、可承諾不含。</b>兩個數字各自誠實：在手是物理事實，可承諾是配貨真的兌現得
 * 了的量。它們對不起來的差額由「已過期」那一欄解釋，而那個差額正是「要報廢還是要進貨」的分歧
 * 點。合起來的版本會讓可承諾說謊，而「有貨卻配不到」那一刻會看起來像配貨壞了。
 *
 * <p><b>該貨主的每一個規格都在，這個倉沒有的顯示 0。</b>那些零就是這個倉缺什麼，而收貨鍵因此
 * 到得了每一個規格。只列有貨的會讓這個倉從未放過的貨品再也進不去。
 */
export function StockPanel({
  ownerId: fixedOwnerId,
  initialScope,
  receiptLocked = false,
  lines,
  receiptConfirmation,
  owners,
  facilitiesOf,
  locationsOf,
  onQuery,
  onConfirmReceipt,
  onScopeChange,
}: StockPanelProps) {
  const ownerFieldId = useId();
  const facilityFieldId = useId();
  const locationFieldId = useId();
  const [selectedOwner, setSelectedOwner] = useState(fixedOwnerId ?? initialScope?.ownerId ?? '');
  const [selectedFacility, setSelectedFacility] = useState(initialScope?.facilityId ?? '');
  const [selectedLocation, setSelectedLocation] = useState(initialScope?.locationId ?? '');
  const [receiving, setReceiving] = useState<StockLine | null>(null);

  const canQuery =
    selectedOwner !== '' && selectedFacility !== '' && selectedLocation !== '';

  function changeScope(nextOwner: string, nextFacility: string) {
    setSelectedOwner(nextOwner);
    setSelectedFacility(nextFacility);
    setSelectedLocation('');
    // 視窗屬於上一個組合的某一列，換了範圍就不該還開著。
    setReceiving(null);
    onScopeChange();
  }

  return (
    <div className={styles.panel}>
      <fieldset className={styles.controls} disabled={receiptLocked} style={{ border: 0, padding: 0, margin: 0 }}>
        {fixedOwnerId ? null : <>
        <div className={styles.field}>
          <label className={styles.label} htmlFor={ownerFieldId}>貨主</label>
          <Select autoSelectSingle
            id={ownerFieldId}
            className={styles.input}
            value={selectedOwner}
            // 倉掛在貨主底下，換貨主就不該留著前一個貨主的倉
            onValueChange={value => changeScope(value, '')}
          >
            <SelectOption value="">請選擇</SelectOption>
            {owners.map((owner) => (
              <SelectOption key={owner.ownerId} value={owner.ownerId}>
                {owner.name}（{owner.code}）
              </SelectOption>
            ))}
          </Select>
        </div>
        </>}
        <div className={styles.field}>
          <label className={styles.label} htmlFor={facilityFieldId}>設施</label>
          <Select autoSelectSingle
            id={facilityFieldId}
            className={styles.input}
            value={selectedFacility}
            onValueChange={value => changeScope(selectedOwner, value)}
            disabled={selectedOwner === ''}
          >
            <SelectOption value="">請選擇</SelectOption>
            {facilitiesOf(selectedOwner).map((facility) => (
              <SelectOption key={facility.facilityId} value={facility.facilityId}>
                {facility.name}（{facility.code}）
              </SelectOption>
            ))}
          </Select>
        </div>
        <div className={styles.field}>
          <label className={styles.label} htmlFor={locationFieldId}>庫位</label>
          <Select autoSelectSingle
            id={locationFieldId}
            className={styles.input}
            value={selectedLocation}
            onValueChange={value => {
              setSelectedLocation(value);
              setReceiving(null);
              onScopeChange();
            }}
            disabled={selectedFacility === ''}
          >
            <SelectOption value="">請選擇</SelectOption>
            {locationsOf(selectedFacility).map((location) => (
              <SelectOption key={location.locationId} value={location.locationId}>
                {location.name}（{location.code}）
              </SelectOption>
            ))}
          </Select>
        </div>
        <button
          type="button"
          onClick={() => onQuery(selectedOwner, selectedFacility, selectedLocation)}
          disabled={!canQuery}
        >
          查詢庫存
        </button>
      </fieldset>

      {/*
        收貨完成後不自動覆寫上面的查詢快照。使用者可以明確重查；同一交易也可能已把新量配置
        給缺貨訂單，所以重查後「在手增加、可承諾不變」也是正常結果。

        手動重查還換來一件事：收貨會順帶喚醒缺貨佇列，所以重查看到的可能是「在手 +50、
        預留也 +50」——貨當場被等待中的單吃掉。那一幕由使用者自己點出來，比在背景悄悄發生好。
      */}
      <ActionState state={receiptConfirmation} pendingLabel="確認收貨中…">
        {(confirmed) => (
          <p className={styles.accepted}>
            <span className={styles.skuValue}>{confirmed.sku}</span> 已完成收貨{' '}
            <span className={styles.eventId}>{confirmed.quantity}</span> 件。
            訂單配置結果請由訂單列表確認。收貨成功後，availability 事件會推進後續分配，不代表原訂單已完成配貨。{' '}
            <button
              type="button"
              className={styles.requery}
              onClick={() => onQuery(selectedOwner, selectedFacility, selectedLocation)}
              disabled={!canQuery}
            >
              重新查詢
            </button>
          </p>
        )}
      </ActionState>

      <ActionState state={lines} pendingLabel="查詢中…">
        {(stockLines) => (
          <StockTable lines={stockLines} receiptLocked={receiptLocked} targetSku={selectedOwner === initialScope?.ownerId && selectedFacility === initialScope.facilityId && selectedLocation === initialScope.locationId ? initialScope.sku : undefined} onConfirmReceipt={(line) => setReceiving(line)} />
        )}
      </ActionState>

      {receiving === null ? null : (
        <ConfirmStockReceiptDialog
          line={receiving}
          ownerName={labelOf(owners.find((owner) => owner.ownerId === selectedOwner))}
          facilityName={labelOf(facilitiesOf(selectedOwner).find((facility) => facility.facilityId === selectedFacility))}
          locationName={labelOf(locationsOf(selectedFacility).find((location) => location.locationId === selectedLocation))}
          onClose={() => setReceiving(null)}
          onSubmit={(input) => {
            // 先關再送；同步結果顯示在列表上方，不讓 dialog 同時承擔表單與結果兩種狀態。
            setReceiving(null);
            onConfirmReceipt({
              ...input,
              ownerId: selectedOwner,
              facilityId: selectedFacility,
              locationId: selectedLocation,
            });
          }}
        />
      )}
    </div>
  );
}

/** 查不到就顯示識別碼——主檔缺一筆不該讓視窗上的欄位變空白。 */
function labelOf(named: { name: string; code: string } | undefined): string {
  return named === undefined ? '—' : `${named.name}（${named.code}）`;
}

/**
 * 一列一個規格，可展開看它的批。
 *
 * <p>順序照傳進來的，**不重排**。那是主檔的款 → 規格順序，而且刻意不隨數量變動：收貨的結果
 * 要手動重查才看得到，排序一旦跟著數量走，你補的那一列就會跳走——而重查的整個目的就是看它
 * 變了什麼。
 */
function StockTable({
  lines,
  receiptLocked,
  targetSku,
  onConfirmReceipt,
}: {
  receiptLocked?: boolean;
  targetSku?: string | undefined;
  lines: StockLine[];
  onConfirmReceipt: (line: StockLine) => void;
}) {
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(new Set());

  if (lines.length === 0) {
    // 這個貨主一個規格都沒有——與「倉是空的」不同，後者仍然列得出每一個規格。
    return <p className={styles.resultFor}>這個貨主的主檔裡沒有任何規格。</p>;
  }

  function toggle(skuCode: string) {
    const next = new Set(expanded);
    if (!next.delete(skuCode)) {
      next.add(skuCode);
    }
    setExpanded(next);
  }

  return (
    <div className={styles.scroller}>
      <table className={styles.table}>
        <thead>
          <tr>
            <th>貨品</th>
            <th className={styles.numberCell}>在手</th>
            <th className={styles.numberCell}>已預留</th>
            <th className={styles.numberCell}>可承諾</th>
            <th className={styles.numberCell}>已過期</th>
            <th />
          </tr>
        </thead>
        {lines.map((line) => (
          <tbody key={line.skuCode} data-target-sku={line.skuCode === targetSku || undefined}>
            <tr>
              <td>
                <button
                  type="button"
                  className={styles.expander}
                  onClick={() => toggle(line.skuCode)}
                  aria-expanded={expanded.has(line.skuCode)}
                >
                  <span aria-hidden="true">{expanded.has(line.skuCode) ? '▾' : '▸'}</span>{' '}
                  {/*
                    主檔查不到時只顯示代碼。壓測用的貨就是這樣——有庫存、沒主檔；漏掉它等於
                    畫面上少報倉庫裡真實存在的貨。
                  */}
                  {line.sku === undefined
                    ? line.skuCode
                    : `${line.sku.productName}・${line.sku.specName}`}{' '}
                  <span className={styles.skuValue}>{line.skuCode}</span>
                </button>
              </td>
              <td className={styles.numberCell}>{line.onHandQuantity}</td>
              <td className={styles.numberCell}>{line.reservedQuantity}</td>
              <td className={styles.numberCell}>{line.availableToPromise}</td>
              {/* 在手與可承諾對不起來時，差額就在這裡——那正是要報廢還是要進貨的分歧點。 */}
              <td className={`${styles.numberCell} ${line.expiredQuantity > 0 ? styles.expiredCell : ''}`}>
                {line.expiredQuantity}
              </td>
              <td>
                {line.skuCode === targetSku ? <span>本次補貨品項 </span> : null}
                <button type="button" disabled={receiptLocked} onClick={() => onConfirmReceipt(line)}>
                  收貨
                </button>
              </td>
            </tr>
            {expanded.has(line.skuCode) ? (
              <tr>
                <td colSpan={6} className={styles.batchCell}>
                  <BatchTable batches={line.batches} />
                </td>
              </tr>
            ) : null}
          </tbody>
        ))}
      </table>
    </div>
  );
}

/**
 * 一個規格的批。
 *
 * <p>過期的列**顯示出來並標記**，不是濾掉：倉庫裡真的有那些貨，而「有貨但出不了」與「什麼都
 * 沒有」要引導出不同的動作（報廢 vs 進貨）。
 *
 * <p>沒有「為什麼不能配」這一欄——「已過期」與「可承諾」合起來就分得出是過期還是被預留光，
 * 第三個欄位只是轉述。
 *
 * <p>順序照後端給的，**不重排**。那就是配貨會取用的順序，而 tie-break 一路排到批的識別碼——
 * 前端重現不了，自己排只會顯示一個永遠不會發生的取用順序。
 *
 * <p>沒有設施欄：整份結果已經鎖在一個倉裡，每一列再印一次只是把查詢條件抄回來。
 */
function BatchTable({ batches }: { batches: readonly StockBatchView[] }) {
  if (batches.length === 0) {
    return <p className={styles.resultFor}>這個倉沒有這個規格的任何批次。</p>;
  }

  return (
    <table className={styles.batchTable}>
      <thead>
        <tr>
          <th>入庫日</th>
          <th>效期</th>
          <th className={styles.numberCell}>在手</th>
          <th className={styles.numberCell}>已預留</th>
          <th className={styles.numberCell}>可承諾</th>
          <th>狀態</th>
        </tr>
      </thead>
      <tbody>
        {batches.map((batch) => (
          <tr key={batch.stockPoolId} className={batch.expired ? styles.expiredRow : undefined}>
            <td className={styles.dateCell}>{batch.inDate}</td>
            <td className={styles.dateCell}>{batch.expiryDate}</td>
            <td className={styles.numberCell}>{batch.onHandQuantity}</td>
            <td className={styles.numberCell}>{batch.reservedQuantity}</td>
            <td className={styles.numberCell}>{batch.availableToPromise}</td>
            <td>
              {batch.expired ? (
                <span className={styles.expiredTag}>已過期</span>
              ) : batch.availableToPromise === 0 ? (
                <span className={styles.exhaustedTag}>已預留完</span>
              ) : (
                <span className={styles.usableTag}>可配</span>
              )}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
