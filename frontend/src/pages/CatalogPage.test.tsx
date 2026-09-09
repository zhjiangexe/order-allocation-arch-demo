import { selectOption } from '../test/selectOption';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as client from '../api/client';
import { CatalogPage } from './CatalogPage';

const OWNER_A = {
  ownerId: '00000000-0000-0000-0000-000000000001',
  code: 'OWNER-A',
  name: '甲貨主',
};
const OWNER_B = {
  ownerId: '00000000-0000-0000-0000-000000000002',
  code: 'OWNER-B',
  name: '乙貨主',
};
const FACILITY_ID = '00000000-0000-0000-0000-000000000011';

describe('CatalogPage', () => {
  beforeEach(() => {
    vi.spyOn(client, 'listOwners').mockResolvedValue([OWNER_A, OWNER_B]);
    vi.spyOn(client, 'listFacilities').mockImplementation(async (ownerId) =>
      ownerId === OWNER_A.ownerId
        ? [{ facilityId: FACILITY_ID, code: 'WH-NORTH', name: '北部倉' }]
        : [],
    );
    vi.spyOn(client, 'listStockLocations').mockResolvedValue([
      {
        locationId: '00000000-0000-0000-0000-000000000021',
        facilityId: FACILITY_ID,
        code: 'WH-NORTH/STOCK',
        name: '北部倉庫存區',
      },
    ]);
    vi.spyOn(client, 'listProducts').mockImplementation(async (ownerId) => [
      {
        productId: `product-${ownerId}`,
        ownerId,
        productCode: 'P-TEA',
        name: ownerId === OWNER_A.ownerId ? '烏龍茶' : '麥茶',
        temperatureZone: 'AMBIENT',
      },
    ]);
    vi.spyOn(client, 'listSkus').mockImplementation(async (ownerId) => [
      {
        skuId: `sku-${ownerId}`,
        ownerId,
        productCode: 'P-TEA',
        skuCode: ownerId === OWNER_A.ownerId ? 'TEA-500' : 'BARLEY-600',
        specName: ownerId === OWNER_A.ownerId ? '500ml 六入' : '600ml',
        weightGram: ownerId === OWNER_A.ownerId ? 3120 : 620,
      },
    ]);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('依貨主顯示其倉庫、庫位、商品與 SKU，且頁面明示唯讀', async () => {
    render(<CatalogPage />);

    expect(await screen.findByRole('heading', { name: '甲貨主' })).toBeInTheDocument();
    expect(screen.getByText('唯讀')).toBeInTheDocument();
    expect(screen.getByText('北部倉')).toBeInTheDocument();
    expect(screen.getByText('WH-NORTH/STOCK')).toBeInTheDocument();
    expect(screen.getByText('烏龍茶')).toBeInTheDocument();
    expect(screen.getByText('TEA-500')).toBeInTheDocument();
  });

  it('切換貨主後不混入另一貨主的同層主檔', async () => {
    render(<CatalogPage />);
    const user = userEvent.setup();
    await screen.findByRole('heading', { name: '甲貨主' });

    await selectOption(user, screen.getByLabelText('貨主'), OWNER_B.ownerId);

    expect(screen.getByRole('heading', { name: '乙貨主' })).toBeInTheDocument();
    expect(screen.getByText('麥茶')).toBeInTheDocument();
    expect(screen.getByText('BARLEY-600')).toBeInTheDocument();
    expect(screen.queryByText('烏龍茶')).not.toBeInTheDocument();
    expect(screen.queryByText('TEA-500')).not.toBeInTheDocument();
  });

  it('可以用 SKU 規格篩選，並只留下命中的 SKU', async () => {
    render(<CatalogPage />);
    const user = userEvent.setup();
    await screen.findByText('TEA-500');

    await user.type(screen.getByLabelText('篩選商品 / SKU'), '六入');

    const table = screen.getByRole('table');
    await waitFor(() => expect(within(table).getByText('TEA-500')).toBeInTheDocument());
    expect(screen.getByText('500ml 六入')).toBeInTheDocument();
  });
});
