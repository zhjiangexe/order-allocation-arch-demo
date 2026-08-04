import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import type { StockLine } from '../api/stockLines';
import type {
  FacilityView,
  OwnerView,
  ReplenishmentAccepted,
  StockBatchView,
} from '../api/types';
import type { AsyncState } from '../hooks/useAsyncAction';
import { StockPanel } from './StockPanel';

const OWNER: OwnerView = {
  ownerId: '00000000-0000-0000-0000-000000000001',
  code: 'OWNER-A',
  name: '甲貨主',
};

const NORTH: FacilityView = {
  facilityId: '00000000-0000-0000-0000-000000000011',
  code: 'WH-NORTH',
  name: '北部倉',
};
const CENTRAL: FacilityView = {
  facilityId: '00000000-0000-0000-0000-000000000012',
  code: 'WH-CENTRAL',
  name: '中部倉',
};

function batch(overrides: Partial<StockBatchView> = {}): StockBatchView {
  return {
    stockPoolId: crypto.randomUUID(),
    inDate: '2026-01-05',
    expiryDate: '2026-12-31',
    onHandQuantity: 10,
    reservedQuantity: 0,
    availableToPromise: 10,
    expired: false,
    ...overrides,
  };
}

function line(skuCode: string, overrides: Partial<StockLine> = {}): StockLine {
  return {
    skuCode,
    sku: {
      skuId: `sku-${skuCode}`,
      ownerId: OWNER.ownerId,
      skuCode,
      productCode: 'P-TEA',
      specName: '500ml',
      weightGram: 520,
      productName: '烏龍茶',
    },
    onHandQuantity: 0,
    reservedQuantity: 0,
    availableToPromise: 0,
    expiredQuantity: 0,
    batches: [],
    ...overrides,
  };
}

function renderPanel(
  lines: AsyncState<StockLine[]>,
  replenishment: AsyncState<ReplenishmentAccepted> = { status: 'idle' },
) {
  const props = {
    lines,
    replenishment,
    owners: [OWNER],
    facilitiesOf: () => [NORTH, CENTRAL],
    onQuery: vi.fn(),
    onReplenish: vi.fn(),
    onScopeChange: vi.fn(),
  };
  render(<StockPanel {...props} />);
  return props;
}

/** 選好貨主與倉別——補貨視窗的唯讀欄位與送出的命令都來自它們。 */
async function chooseScope(user: ReturnType<typeof userEvent.setup>) {
  await user.selectOptions(screen.getByLabelText('貨主'), OWNER.ownerId);
  await user.selectOptions(screen.getByLabelText('倉別'), NORTH.facilityId);
}

const idle: AsyncState<StockLine[]> = { status: 'idle' };
const loaded = (lines: StockLine[]): AsyncState<StockLine[]> => ({ status: 'success', data: lines });

/** 展開鍵就是貨品那一格，用 aria-expanded 找得到而不必依賴文字。 */
const expanders = () => screen.getAllByRole('button', { expanded: false });

/**
 * 展開後的批次表。
 *
 * 斷言要收在這裡面，因為「已過期」在畫面上有兩個意思：外層是一欄件數，批這一層是一個狀態
 * 標籤。兩者都該叫這個名字——同一件事在兩個粒度上——所以收斂查詢範圍，而不是改 UI 用字。
 */
const batchTable = () => within(screen.getAllByRole('table')[1]!);

describe('StockPanel 的查詢軸', () => {
  it('貨主與倉別都選了才查得動', async () => {
    const props = renderPanel(idle);
    const user = userEvent.setup();

    expect(screen.getByRole('button', { name: '查詢庫存' })).toBeDisabled();
    await user.selectOptions(screen.getByLabelText('貨主'), OWNER.ownerId);
    expect(screen.getByRole('button', { name: '查詢庫存' })).toBeDisabled();

    await user.selectOptions(screen.getByLabelText('倉別'), NORTH.facilityId);
    await user.click(screen.getByRole('button', { name: '查詢庫存' }));

    // 少了倉別，問的是一個沒有任何一次配貨取用得了的池——配貨從不跨倉。
    expect(props.onQuery).toHaveBeenCalledExactlyOnceWith(OWNER.ownerId, NORTH.facilityId);
  });

  it('未選貨主時倉別不可選——倉是掛在貨主底下的', () => {
    renderPanel(idle);

    expect(screen.getByLabelText('倉別')).toBeDisabled();
  });

  it('換貨主會清空倉別並作廢畫面上的結果', async () => {
    const props = renderPanel(idle);
    const user = userEvent.setup();

    await user.selectOptions(screen.getByLabelText('貨主'), OWNER.ownerId);
    await user.selectOptions(screen.getByLabelText('倉別'), CENTRAL.facilityId);
    await user.selectOptions(screen.getByLabelText('貨主'), '');

    // 兩個貨主可能共用同一個倉，留著看起來像仍然有效，但有效與否取決於指派關係。
    expect(screen.getByLabelText('倉別')).toHaveValue('');
    expect(props.onScopeChange).toHaveBeenCalled();
  });

  it('換倉別也作廢結果——畫面上的數字屬於上一個倉', async () => {
    const props = renderPanel(idle);
    const user = userEvent.setup();

    await user.selectOptions(screen.getByLabelText('貨主'), OWNER.ownerId);
    await user.selectOptions(screen.getByLabelText('倉別'), NORTH.facilityId);
    props.onScopeChange.mockClear();
    await user.selectOptions(screen.getByLabelText('倉別'), CENTRAL.facilityId);

    expect(props.onScopeChange).toHaveBeenCalled();
  });

  it('尚未查詢時就說明為什麼兩者都要選', () => {
    renderPanel(idle);

    // 使用者面對這個疑問是在按任何按鈕之前，所以不能只跟著結果出現。
    expect(screen.getByText(/貨主與倉別都要選/)).toBeInTheDocument();
  });

  it('查詢失敗時就地顯示失敗，不顯示任何列', () => {
    renderPanel({ status: 'failure', message: '後端不可用' });

    expect(screen.getByText(/後端不可用/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '補貨' })).not.toBeInTheDocument();
  });
});

describe('StockPanel 的貨品列表', () => {
  it('四個數量欄各自顯示，含「已過期」那一欄', () => {
    renderPanel(loaded([
      line('SKU-TEA-500', {
        onHandQuantity: 155,
        reservedQuantity: 80,
        availableToPromise: 50,
        expiredQuantity: 25,
      }),
    ]));

    // 在手 155 而可承諾只有 50，差額 25 就在「已過期」那一欄——那正是要報廢還是要進貨的
    // 分歧點。少了那一欄，三個數字對不起來會看起來像算錯。
    const row = screen.getByRole('row', { name: /SKU-TEA-500/ });
    expect(within(row).getByText('155')).toBeInTheDocument();
    expect(within(row).getByText('80')).toBeInTheDocument();
    expect(within(row).getByText('50')).toBeInTheDocument();
    expect(within(row).getByText('25')).toBeInTheDocument();
  });

  it('順序照傳進來的，不隨數量重排', () => {
    renderPanel(loaded([
      line('SKU-A', { onHandQuantity: 1 }),
      line('SKU-B', { onHandQuantity: 999 }),
      line('SKU-C', { onHandQuantity: 50 }),
    ]));

    // 補貨的結果要手動重查才看得到；排序一旦跟著數量走，你補的那一列就會跳走——而重查的
    // 整個目的就是看它變了什麼。
    const codes = expanders().map((button) => button.textContent ?? '');
    expect(codes[0]).toContain('SKU-A');
    expect(codes[1]).toContain('SKU-B');
    expect(codes[2]).toContain('SKU-C');
  });

  it('這個倉沒有的規格顯示 0，而且照樣補得了貨', async () => {
    renderPanel(loaded([line('SKU-NONE')]));
    const user = userEvent.setup();

    // 那些零就是「這個倉缺什麼」。只列有貨的會讓這個倉從未放過的貨品再也進不去。
    const row = screen.getByRole('row', { name: /SKU-NONE/ });
    expect(within(row).getAllByText('0')).toHaveLength(4);

    await user.click(within(row).getByRole('button', { name: '補貨' }));
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('主檔查不到的規格只顯示代碼，不留白也不消失', () => {
    renderPanel(loaded([line('HOT-SKU', { sku: undefined, onHandQuantity: 500 })]));

    // 有庫存沒主檔的貨是真的存在（壓測用的就是）。漏掉它等於少報倉庫裡真實存在的貨。
    expect(screen.getByRole('row', { name: /HOT-SKU/ })).toBeInTheDocument();
  });

  it('空倉仍列出全部規格，每一列都能補貨', () => {
    renderPanel(loaded([line('SKU-A'), line('SKU-B'), line('SKU-C')]));

    // 空倉是正常答案，而且那正是新倉上線時的狀態——最需要補貨的時候。
    expect(screen.getAllByRole('button', { name: '補貨' })).toHaveLength(3);
  });
});

describe('StockPanel 的展開', () => {
  it('預設收合，展開後逐批顯示且順序照傳進來的', async () => {
    renderPanel(loaded([
      line('SKU-1', {
        batches: [batch({ expiryDate: '2026-08-31' }), batch({ expiryDate: '2027-01-31' })],
      }),
    ]));
    const user = userEvent.setup();

    expect(screen.queryByText('2026-08-31')).not.toBeInTheDocument();

    await user.click(expanders()[0]!);

    // 那個順序就是配貨會取用的順序，tie-break 一路排到批的識別碼——前端重現不了，只能照抄。
    const dates = screen.getAllByText(/^20\d\d-\d\d-\d\d$/).map((cell) => cell.textContent);
    expect(dates.indexOf('2026-08-31')).toBeLessThan(dates.indexOf('2027-01-31'));
  });

  it('過期的批要顯示並標記，不得被濾掉', async () => {
    renderPanel(loaded([
      line('SKU-1', {
        // 三個數字刻意互異，斷言才指得出是「在手」那一格。
        batches: [
          batch({
            onHandQuantity: 25,
            reservedQuantity: 5,
            availableToPromise: 20,
            expired: true,
          }),
        ],
      }),
    ]));
    const user = userEvent.setup();

    await user.click(expanders()[0]!);

    // 濾掉會讓「有 25 件但一件都出不了」與「什麼都沒有」長得一樣，而前者要報廢、後者要進貨。
    expect(batchTable().getByText('已過期')).toBeInTheDocument();
    expect(batchTable().getByText('25')).toBeInTheDocument();
  });

  it('沒過期但被預留光的批標成「已預留完」，與過期分開', async () => {
    renderPanel(loaded([
      line('SKU-1', {
        batches: [batch({ onHandQuantity: 40, reservedQuantity: 40, availableToPromise: 0 })],
      }),
    ]));
    const user = userEvent.setup();

    await user.click(expanders()[0]!);

    // 配貨眼中兩者相同，但操作上一個要報廢、一個只是等出貨。
    expect(batchTable().getByText('已預留完')).toBeInTheDocument();
    expect(batchTable().queryByText('已過期')).not.toBeInTheDocument();
  });

  it('這個倉沒有這個規格時展開要明說，不留空白表格', async () => {
    renderPanel(loaded([line('SKU-NONE')]));
    const user = userEvent.setup();

    await user.click(expanders()[0]!);

    expect(screen.getByText(/沒有這個規格的任何批次/)).toBeInTheDocument();
  });

  it('再按一次收合', async () => {
    renderPanel(loaded([line('SKU-1', { batches: [batch()] })]));
    const user = userEvent.setup();

    await user.click(expanders()[0]!);
    await user.click(screen.getByRole('button', { expanded: true }));

    expect(screen.queryByText('入庫日')).not.toBeInTheDocument();
  });

  it('展開一列不會連帶展開其他列', async () => {
    renderPanel(loaded([
      line('SKU-A', { batches: [batch({ inDate: '2026-03-03' })] }),
      line('SKU-B', { batches: [batch({ inDate: '2026-04-04' })] }),
    ]));
    const user = userEvent.setup();

    await user.click(expanders()[0]!);

    expect(screen.getByText('2026-03-03')).toBeInTheDocument();
    expect(screen.queryByText('2026-04-04')).not.toBeInTheDocument();
  });
});

describe('StockPanel 的補貨視窗', () => {
  const stocked = line('SKU-1', {
    batches: [
      batch({ inDate: '2026-05-01', expiryDate: '2026-09-30' }),
      batch({ inDate: '2026-06-01', expiryDate: '2027-03-31' }),
    ],
  });

  it('點某一列的補貨鍵才開視窗，貨主、倉別、貨品唯讀顯示', async () => {
    renderPanel(loaded([stocked]));
    const user = userEvent.setup();
    await chooseScope(user);

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '補貨' }));

    // 三者已經由查詢與被點的那一列決定。可改就會出現「點的是北部倉、寫進去的是中部倉」
    // 這種矛盾，而送出後列表不會動、畫面也沒有東西解釋為什麼。
    const dialog = within(screen.getByRole('dialog'));
    expect(dialog.getByText(/甲貨主/)).toBeInTheDocument();
    expect(dialog.getByText(/北部倉/)).toBeInTheDocument();
    expect(dialog.getByText('SKU-1')).toBeInTheDocument();
    // 但它們仍然看得到——補貨要五個維度才決定得了寫進哪一列。
    expect(dialog.queryByLabelText('倉別')).not.toBeInTheDocument();
  });

  it('兩個日期預帶最近效期那一批', async () => {
    renderPanel(loaded([stocked]));
    const user = userEvent.setup();
    await chooseScope(user);
    await user.click(screen.getByRole('button', { name: '補貨' }));

    // 不改直接送就是往現有的批加貨；要開新批才改日期。兩種意圖都一步到位。
    expect(screen.getByLabelText('入庫日')).toHaveValue('2026-05-01');
    expect(screen.getByLabelText('效期')).toHaveValue('2026-09-30');
  });

  it('已過期的批不拿來預帶——往它加貨等於製造看不見的死庫存', async () => {
    renderPanel(loaded([
      line('SKU-1', {
        batches: [
          batch({ inDate: '2025-01-01', expiryDate: '2026-01-31', expired: true }),
          batch({ inDate: '2026-06-01', expiryDate: '2027-03-31' }),
        ],
      }),
    ]));
    const user = userEvent.setup();
    await chooseScope(user);
    await user.click(screen.getByRole('button', { name: '補貨' }));

    // 過期的批排在 FEFO 最前面，照抄第一批就會補到一批配貨永遠取不到的貨上。
    expect(screen.getByLabelText('入庫日')).toHaveValue('2026-06-01');
    expect(screen.getByLabelText('效期')).toHaveValue('2027-03-31');
  });

  it('這個倉沒有這個規格時兩個日期留空，並說明會開一批新的', async () => {
    renderPanel(loaded([line('SKU-NONE')]));
    const user = userEvent.setup();
    await chooseScope(user);
    await user.click(screen.getByRole('button', { name: '補貨' }));

    expect(screen.getByLabelText('入庫日')).toHaveValue('');
    expect(screen.getByLabelText('效期')).toHaveValue('');
    expect(screen.getByText(/開一批新的/)).toBeInTheDocument();
  });

  it('送出時把貨主、倉別、規格與三個輸入一起帶出去', async () => {
    const props = renderPanel(loaded([stocked]));
    const user = userEvent.setup();
    await chooseScope(user);
    await user.click(screen.getByRole('button', { name: '補貨' }));
    await user.clear(screen.getByLabelText('補貨數量'));
    await user.type(screen.getByLabelText('補貨數量'), '120');
    await user.click(screen.getByRole('button', { name: '送出補貨' }));

    expect(props.onReplenish).toHaveBeenCalledExactlyOnceWith({
      ownerId: OWNER.ownerId,
      facilityId: NORTH.facilityId,
      sku: 'SKU-1',
      inDate: '2026-05-01',
      expiryDate: '2026-09-30',
      quantity: 120,
    });
  });

  it.each([
    ['入庫日為空', '入庫日'],
    ['效期為空', '效期'],
  ])('%s 時不可送出', async (_case, field) => {
    renderPanel(loaded([stocked]));
    const user = userEvent.setup();
    await chooseScope(user);
    await user.click(screen.getByRole('button', { name: '補貨' }));
    await user.clear(screen.getByLabelText(field));

    // 缺任一個維度就決定不了寫進哪一列。無效的補貨不該換來一次沒有必要的往返。
    expect(screen.getByRole('button', { name: '送出補貨' })).toBeDisabled();
  });

  it.each([['0'], ['-3'], ['']])('數量為 %s 時不可送出', async (quantity) => {
    renderPanel(loaded([stocked]));
    const user = userEvent.setup();
    await chooseScope(user);
    await user.click(screen.getByRole('button', { name: '補貨' }));
    await user.clear(screen.getByLabelText('補貨數量'));
    if (quantity !== '') {
      await user.type(screen.getByLabelText('補貨數量'), quantity);
    }

    expect(screen.getByRole('button', { name: '送出補貨' })).toBeDisabled();
  });

  it('送出後關視窗', async () => {
    renderPanel(loaded([stocked]));
    const user = userEvent.setup();
    await chooseScope(user);
    await user.click(screen.getByRole('button', { name: '補貨' }));
    await user.click(screen.getByRole('button', { name: '送出補貨' }));

    // 視窗留著什麼都不會顯示——結果是非同步的，而「已受理」屬於列表那一層。
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('取消不送出任何東西', async () => {
    const props = renderPanel(loaded([stocked]));
    const user = userEvent.setup();
    await chooseScope(user);
    await user.click(screen.getByRole('button', { name: '補貨' }));
    await user.click(screen.getByRole('button', { name: '取消' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(props.onReplenish).not.toHaveBeenCalled();
  });

  it('換倉別會關掉視窗——它屬於上一個組合的某一列', async () => {
    renderPanel(loaded([stocked]));
    const user = userEvent.setup();
    await chooseScope(user);
    await user.click(screen.getByRole('button', { name: '補貨' }));
    await user.selectOptions(screen.getByLabelText('倉別'), CENTRAL.facilityId);

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});

describe('StockPanel 的補貨結果', () => {
  const accepted: AsyncState<ReplenishmentAccepted> = {
    status: 'success',
    data: { eventId: 'evt-1', sku: 'SKU-1', quantity: 500 },
  };

  it('受理後顯示事件識別碼，並說明數字要重查才會更新', async () => {
    renderPanel(loaded([line('SKU-1')]), accepted);

    expect(screen.getByText('evt-1')).toBeInTheDocument();
    expect(screen.getByText(/要重查才會更新/)).toBeInTheDocument();
  });

  it('不自動重查，重查鍵按了才發', async () => {
    const props = renderPanel(loaded([line('SKU-1')]), accepted);
    const user = userEvent.setup();
    await chooseScope(user);

    // **刻意不 mockClear。** 自動重查若真的存在，會是掛載時的一個 effect；先清掉再斷言就把
    // 唯一的證據擦掉了，測試會永遠通過。
    //
    // 補貨回 202——立刻重查很可能查到還沒變的數字，而畫面分不出「還沒處理到」與「處理完了
    // 但真的沒變」。而且補貨會順帶喚醒缺貨佇列，那一幕值得由使用者自己點出來。
    expect(props.onQuery).not.toHaveBeenCalled();

    await user.click(screen.getByRole('button', { name: '重新查詢' }));
    expect(props.onQuery).toHaveBeenCalledExactlyOnceWith(OWNER.ownerId, NORTH.facilityId);
  });

  it('補貨失敗時就地顯示失敗，且畫面上不出現事件識別碼', () => {
    renderPanel(loaded([line('SKU-1')]), { status: 'failure', message: 'Kafka 不可用' });

    // 失敗就不該有任何「已受理」的跡象——事件識別碼是受理的證據。
    expect(screen.getByText(/Kafka 不可用/)).toBeInTheDocument();
    expect(screen.queryByText(/事件識別碼/)).not.toBeInTheDocument();
  });
});
