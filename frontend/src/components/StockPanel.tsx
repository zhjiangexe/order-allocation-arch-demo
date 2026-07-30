import { useId, useState } from 'react';

import type {
  FulfillmentNodeView,
  OwnerView,
  ReplenishmentAccepted,
  StockBatchView,
  StockPoolView,
} from '../api/types';
import type { AsyncState } from '../hooks/useAsyncAction';
import { ActionState } from './ActionState';
import styles from './StockPanel.module.css';

export interface ReplenishInput {
  ownerId: string;
  nodeId: string;
  sku: string;
  inDate: string;
  expiryDate: string;
  quantity: number;
}

interface StockPanelProps {
  stock: AsyncState<StockPoolView>;
  replenishment: AsyncState<ReplenishmentAccepted>;
  owners: OwnerView[];
  /** 所選貨主掛的倉。補貨要指定加到哪一個倉的庫存。 */
  nodesOf: (ownerId: string) => readonly FulfillmentNodeView[];
  /** 所選貨主的 SKU 代碼，作為輸入建議。刻意不是限制——見下方 datalist 的說明。 */
  skuCodesOf: (ownerId: string) => string[];
  /** 把批次列表裡的 `nodeId` 換成看得懂的倉名；查不到就顯示 id。 */
  nodeLabel: (ownerId: string, nodeId: string) => string;
  onQuery: (ownerId: string, sku: string) => void;
  onReplenish: (input: ReplenishInput) => void;
  /** 改動貨主或 SKU 時作廢畫面上的結果——它們屬於上一個組合。 */
  onScopeChange: () => void;
}

/**
 * 查庫存與補貨放同一個面板、共用同一個貨主與 SKU，因為它們是一個連續動作：查到可承諾量是
 * 0、補一批、再查一次確認。拆成兩塊會把這條敘事切斷。
 *
 * <p>共用欄位的代價是結果會失去歸屬：查完一個組合、把欄位改掉，畫面上的數字仍是前一個組合
 * 的。因此兩件事都要做——結果自己標出它屬於哪個 SKU（取自後端回應，不是輸入框，兩者在查詢
 * 往返之間可能已經不同），而且改動欄位就作廢前一次的結果。
 *
 * <p><b>貨主現在是兩邊都必填的。</b>先前這裡有一段說明「補貨要選貨主、查詢不用」，理由是庫存
 * 尚未按貨主分開。庫存分批之後那個不對稱消失了：同碼 SKU 在兩個貨主名下是兩批不同的貨，查詢
 * 不帶貨主等於問一個沒有答案的問題（後端直接回 400）。
 *
 * <p><b>結果是批次列表而不是三個數字。</b>一個 SKU 分成幾批、每批的效期與過期與否，正是這一頁
 * 要回答的問題；摺成加總之後「有 100 件但一件都出不了」與「什麼都沒有」會長得一模一樣。列的
 * 順序就是配貨會取用的順序，所以第一列就是「下一張單會吃到的那一批」。
 */
export function StockPanel({
  stock,
  replenishment,
  owners,
  nodesOf,
  skuCodesOf,
  nodeLabel,
  onQuery,
  onReplenish,
  onScopeChange,
}: StockPanelProps) {
  const ownerFieldId = useId();
  const nodeFieldId = useId();
  const skuId = useId();
  const skuOptionsId = useId();
  const inDateId = useId();
  const expiryDateId = useId();
  const quantityId = useId();
  const [selectedOwner, setSelectedOwner] = useState('');
  const [selectedNode, setSelectedNode] = useState('');
  const [sku, setSku] = useState('');
  const [inDate, setInDate] = useState('');
  const [expiryDate, setExpiryDate] = useState('');
  const [quantity, setQuantity] = useState('500');

  const trimmedSku = sku.trim();
  const parsedQuantity = Number(quantity);
  const canQuery = selectedOwner !== '' && trimmedSku !== '';
  // 五個維度加數量缺任一個就不送。無效的補貨不該換來一次沒有必要的往返。
  const canReplenish =
    canQuery &&
    selectedNode !== '' &&
    inDate !== '' &&
    expiryDate !== '' &&
    Number.isInteger(parsedQuantity) &&
    parsedQuantity > 0;

  return (
    <div className={styles.panel}>
      {/*
        這段必須在查詢之前就在畫面上：使用者面對「為什麼補貨要填這麼多欄位」的疑問是在按任何
        按鈕之前。
      */}
      <p className={styles.scopeNote}>
        查詢與補貨都要選貨主——同碼 SKU 在兩個貨主名下是兩批不同的貨，不可互相調用。
        補貨還要指定倉別、入庫日與效期：這五個維度合起來決定這批貨加到哪一列，命中既有列就加
        數量，否則新開一列。
      </p>
      <div className={styles.controls}>
        <div className={styles.field}>
          <label className={styles.label} htmlFor={ownerFieldId}>貨主</label>
          <select
            id={ownerFieldId}
            className={styles.input}
            value={selectedOwner}
            onChange={(event) => {
              setSelectedOwner(event.target.value);
              // 倉與 SKU 都掛在貨主底下，換貨主就不該留著前一個貨主的倉
              setSelectedNode('');
              onScopeChange();
            }}
          >
            <option value="">請選擇</option>
            {owners.map((owner) => (
              <option key={owner.ownerId} value={owner.ownerId}>
                {owner.name}（{owner.code}）
              </option>
            ))}
          </select>
        </div>
        <div className={styles.field}>
          <label className={styles.label} htmlFor={skuId}>SKU</label>
          <input
            id={skuId}
            className={styles.input}
            list={skuOptionsId}
            value={sku}
            onChange={(event) => {
              setSku(event.target.value);
              onScopeChange();
            }}
            placeholder="HOT-SKU"
          />
          {/*
            用 datalist 給建議而不是換成下拉：庫存與主檔不是同一組資料，壓測用的 HOT-SKU
            有庫存卻沒有主檔，改成只能選就會讓它查不到。
          */}
          <datalist id={skuOptionsId}>
            {skuCodesOf(selectedOwner).map((code) => (
              <option key={code} value={code} />
            ))}
          </datalist>
        </div>
        <button
          type="button"
          onClick={() => onQuery(selectedOwner, trimmedSku)}
          disabled={!canQuery}
        >
          查詢庫存
        </button>
      </div>

      <div className={styles.controls}>
        <div className={styles.field}>
          <label className={styles.label} htmlFor={nodeFieldId}>補貨倉別</label>
          <select
            id={nodeFieldId}
            className={styles.input}
            value={selectedNode}
            onChange={(event) => setSelectedNode(event.target.value)}
            disabled={selectedOwner === ''}
          >
            <option value="">請選擇</option>
            {nodesOf(selectedOwner).map((node) => (
              <option key={node.nodeId} value={node.nodeId}>
                {node.name}（{node.code}）
              </option>
            ))}
          </select>
        </div>
        <div className={styles.field}>
          <label className={styles.label} htmlFor={inDateId}>入庫日</label>
          <input
            id={inDateId}
            className={styles.input}
            type="date"
            value={inDate}
            onChange={(event) => setInDate(event.target.value)}
          />
        </div>
        <div className={styles.field}>
          <label className={styles.label} htmlFor={expiryDateId}>效期</label>
          <input
            id={expiryDateId}
            className={styles.input}
            type="date"
            value={expiryDate}
            onChange={(event) => setExpiryDate(event.target.value)}
          />
        </div>
        <div className={styles.field}>
          <label className={styles.label} htmlFor={quantityId}>補貨數量</label>
          <input
            id={quantityId}
            className={`${styles.input} ${styles.quantity}`}
            value={quantity}
            onChange={(event) => setQuantity(event.target.value)}
            inputMode="numeric"
          />
        </div>
        <button
          type="button"
          onClick={() =>
            onReplenish({
              ownerId: selectedOwner,
              nodeId: selectedNode,
              sku: trimmedSku,
              inDate,
              expiryDate,
              quantity: parsedQuantity,
            })
          }
          disabled={!canReplenish}
        >
          觸發補貨
        </button>
      </div>

      <ActionState state={stock} pendingLabel="查詢中…">
        {(pool) => (
          <div className={styles.result}>
            <p className={styles.resultFor}>
              <span className={styles.skuValue}>{pool.sku}</span> 的批次，依配貨會取用的順序
              排列——第一列就是下一張單會吃到的那一批。
            </p>
            <BatchTable
              batches={pool.batches}
              nodeLabel={(nodeId) => nodeLabel(selectedOwner, nodeId)}
            />
          </div>
        )}
      </ActionState>

      <ActionState state={replenishment} pendingLabel="發布補貨事件中…">
        {(accepted) => (
          <p className={styles.accepted}>
            <span className={styles.skuValue}>{accepted.sku}</span> 的補貨事件已受理，事件識別碼{' '}
            <span className={styles.eventId}>{accepted.eventId}</span>
            。配置是非同步的——再查一次庫存、或到訂單頁按重新整理，才看得到結果。
          </p>
        )}
      </ActionState>
    </div>
  );
}

/**
 * 批次列表。
 *
 * <p>過期的列**顯示出來並標記**，不是濾掉：倉庫裡真的有那些貨，而「有貨但出不了」與「什麼都
 * 沒有」要引導出不同的動作（報廢 vs 進貨）。
 *
 * <p>沒有「為什麼不能配」這一欄——「已過期」與「可承諾」合起來就分得出是過期還是被預留光，
 * 第三個欄位只是轉述。
 */
function BatchTable({
  batches,
  nodeLabel,
}: {
  batches: StockBatchView[];
  nodeLabel: (nodeId: string) => string;
}) {
  if (batches.length === 0) {
    return <p className={styles.resultFor}>這個 SKU 目前沒有任何批次。</p>;
  }

  return (
    <div className={styles.scroller}>
      <table className={styles.table}>
        <thead>
          <tr>
            <th>倉別</th>
            <th>入庫日</th>
            <th>效期</th>
            <th>在手</th>
            <th>已預留</th>
            <th>可承諾</th>
            <th>狀態</th>
          </tr>
        </thead>
        <tbody>
          {batches.map((batch) => (
            <tr key={batch.stockPoolId} className={batch.expired ? styles.expiredRow : undefined}>
              <td>{nodeLabel(batch.nodeId)}</td>
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
    </div>
  );
}
