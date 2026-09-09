import { act, render, screen } from '@testing-library/react';
import { MemoryRouter, useNavigate, useLocation } from 'react-router';
import { describe, expect, it } from 'vitest';
import samples from '../test/fixtures/fulfillment.json';
import { detailUrl, parseDetail, parseReceiptContext, receiptUrl, returnToFulfillment,
  validateReceiptContext } from './navigation';
import type { ReceiptContext } from './navigation';

const order = samples.events.order;
const context: ReceiptContext = {
  ownerId: order.ownerId, facilityId: order.facilityId,
  locationId: samples.events.stockOperation.operation.fromLocationId,
  sku: 'SKU + & 台灣', returnOrderId: order.orderId,
  list: { page: '/allocations', filters: { ownerId: order.ownerId, sku: 'original filter', source: 'ORDER', limit: '200' } },
};

describe('fulfillment navigation', () => {
  it('opens/closes a deep link while preserving list filters', () => {
    const opened = detailUrl('/orders', '?limit=50&sku=A%26B', order.orderId);
    expect(parseDetail('/orders', opened.split('?')[1]!)).toMatchObject({ orderId: order.orderId });
    expect(detailUrl('/orders', opened.split('?')[1]!, null)).toBe('/orders?limit=50&sku=A%26B');
    expect(parseDetail('/orders', '?orderId=invalid').invalidOrderId).toBe(true);
    expect(() => detailUrl('/orders', '', 'invalid')).toThrow('Invalid orderId');
  });
  it('round trips receipt context and returns to the selected order and original filters', () => {
    const url = receiptUrl(context);
    expect(parseReceiptContext(url.split('?')[1]!)).toEqual(context);
    const returned = returnToFulfillment(context);
    expect(parseDetail('/allocations', returned.split('?')[1]!)).toEqual({
      orderId: order.orderId, invalidOrderId: false, list: context.list,
    });
  });
  it.each(['https://evil.example', '//evil.example', '/catalog', '/orders?redirect=evil'])(
    'rejects arbitrary return destinations: %s', page => {
      const params = new URLSearchParams(receiptUrl(context).split('?')[1]);
      params.set('returnPage', page);
      expect(parseReceiptContext(params.toString())).toBeNull();
    },
  );
  it.each(['ownerId', 'facilityId', 'locationId', 'returnOrderId'])(
    'rejects invalid %s', key => {
      const params = new URLSearchParams(receiptUrl(context).split('?')[1]);
      params.set(key, 'invalid');
      expect(parseReceiptContext(params.toString())).toBeNull();
    },
  );
  it('waits for the right catalog and rejects mismatched location/SKU ownership', () => {
    const catalog = { ownerId: context.ownerId,
      owners: [{ ownerId: context.ownerId, code: 'O', name: 'Owner' }],
      facilities: [{ facilityId: context.facilityId, code: 'F', name: 'Facility' }],
      locations: [{ locationId: context.locationId, facilityId: context.facilityId, code: 'L', name: 'Location' }],
      skus: [{ skuId: order.orderId, ownerId: context.ownerId, skuCode: context.sku,
        productCode: 'P', specName: 'S', weightGram: 1 }],
    };
    expect(validateReceiptContext(context, { ...catalog, locations: null })).toBe('pending');
    expect(validateReceiptContext(context, { ...catalog, ownerId: null })).toBe('pending');
    expect(validateReceiptContext(context, catalog)).toBe('valid');
    expect(validateReceiptContext(context, { ...catalog,
      locations: [{ ...catalog.locations[0]!, facilityId: 'different' }] })).toBe('invalid');
    expect(validateReceiptContext(context, { ...catalog,
      skus: [{ ...catalog.skus[0]!, ownerId: 'different' }] })).toBe('invalid');
  });
  it('re-parses detail when navigating browser history backward/forward', async () => {
    function DetailProbe() {
      const navigate = useNavigate();
      const location = useLocation();
      const detail = parseDetail(location.pathname, location.search);
      return <>
        <output data-testid="detail">{detail.orderId ?? 'closed'}:{detail.list.filters.sku}</output>
        <button onClick={() => void navigate(-1)}>Back</button>
        <button onClick={() => void navigate(1)}>Forward</button>
      </>;
    }
    const { unmount } = render(<MemoryRouter initialEntries={[
      '/orders?sku=original', detailUrl('/orders', '?sku=original', order.orderId),
    ]}><DetailProbe /></MemoryRouter>);
    expect(screen.getByTestId('detail')).toHaveTextContent(`${order.orderId}:original`);
    await act(async () => { screen.getByText('Back').click(); });
    expect(screen.getByTestId('detail')).toHaveTextContent('closed:original');
    await act(async () => { screen.getByText('Forward').click(); });
    expect(screen.getByTestId('detail')).toHaveTextContent(`${order.orderId}:original`);
    unmount();
  });
});
