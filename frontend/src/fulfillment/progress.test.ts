import { describe, expect, it } from 'vitest';
import samples from '../test/fixtures/fulfillment.json';
import type { OrderFulfillmentView } from '../api/types';
import { fulfillmentProgress } from './progress';

const fixture = (mode: 'events' | 'temporal'): OrderFulfillmentView => structuredClone(samples[mode]);

describe('fulfillmentProgress', () => {
  it.each(['events', 'temporal'] as const)('%s uses correlated completion evidence', mode => {
    const view = fixture(mode);
    expect(fulfillmentProgress(view)).toMatchObject({ state: 'completed', stopTracking: true });
    expect(view.stockOperation?.moves[0]?.batches[0]).toHaveProperty('stockQuantId');
    expect(view.order.lines[0]).toHaveProperty('orderLineId');
    expect(view.order.lines[0]).not.toHaveProperty('status');
  });
  it.each(['shipment', 'order', 'operation', 'source', 'unit', 'missing', 'duplicate'])(
    'does not accept inconsistent %s evidence', kind => {
      const view = fixture('events');
      if (kind === 'shipment') view.order.fulfilledByShipmentId = 'other';
      if (kind === 'order') view.shipments[0]!.orderId = 'other';
      if (kind === 'operation') view.shipments[0]!.stockOperationId = 'other';
      if (kind === 'source') view.stockOperation!.source.type = 'OTHER';
      if (kind === 'unit') view.stockOperation!.source.operationUnitKey = 'OTHER';
      if (kind === 'missing') view.stockOperation = null;
      if (kind === 'duplicate') view.shipments.push(structuredClone(view.shipments[0]!));
      expect(fulfillmentProgress(view)).toMatchObject({ state: 'unconfirmed', businessComplete: false });
    },
  );
  it('ignores an unrelated completed shipment', () => {
    const view = fixture('events');
    view.shipments.unshift({ ...view.shipments[0]!, shipmentId: 'other' });
    view.shipments[1]!.status = 'PICKING';
    expect(fulfillmentProgress(view).state).toBe('unconfirmed');
  });
  it.each(['orderId', 'stockOperationId', 'shipmentId', 'shipmentTerminalStatus', 'phase', 'outcome'] as const)(
    'Temporal requires its own %s evidence', field => {
      const view = fixture('temporal');
      view.temporalWorkflow![field] = 'OTHER';
      expect(fulfillmentProgress(view).state).not.toBe('completed');
    },
  );
  it('waits for Temporal even after business completion', () => {
    const view = fixture('temporal');
    view.temporalWorkflow!.phase = 'ORDER_COMPLETION';
    view.temporalWorkflow!.outcome = null;
    expect(fulfillmentProgress(view)).toMatchObject({ state: 'unconfirmed', stopTracking: false });
    expect(view.temporalWorkflow).toHaveProperty('updatedAt');
  });
  it('NOT_FOUND remains Temporal and can continue tracking', () => {
    const view = fixture('temporal');
    view.temporalWorkflow = null;
    view.workflowQueryStatus = 'NOT_FOUND';
    expect(fulfillmentProgress(view)).toMatchObject({ state: 'waiting', stopTracking: false });
    view.workflowQueryStatus = 'UNAVAILABLE';
    expect(fulfillmentProgress(view)).toMatchObject({ state: 'unavailable', stopTracking: true });
  });
  it.each(['CANCELLED', 'NEW_STATE', null])('stops safely on order status %s', status => {
    const view = fixture('events');
    view.order.status = status;
    expect(fulfillmentProgress(view).stopTracking).toBe(true);
    expect(fulfillmentProgress(view).state).not.toBe('completed');
  });
  it('does not mistake long allocation waiting for failure', () => {
    const view = fixture('events');
    view.order.status = 'PENDING';
    view.order.fulfilledByShipmentId = null;
    view.stockOperation!.operation.state = 'CONFIRMED';
    view.stockOperation!.operation.enqueuedAt = '2000-01-01T00:00:00Z';
    view.shipments = [];
    expect(fulfillmentProgress(view)).toMatchObject({ state: 'waiting', stopTracking: false });
  });
});
