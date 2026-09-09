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
    'Temporal technical %s does not override business completion', field => {
      const view = fixture('temporal');
      view.temporalWorkflow![field] = 'OTHER';
      expect(fulfillmentProgress(view)).toEqual(fulfillmentProgress({ ...view, orchestrationMode: 'events', temporalWorkflow: null, workflowQueryStatus: 'NOT_APPLICABLE' }));
    },
  );
  it('completes without waiting for Temporal activity response', () => {
    const view = fixture('temporal');
    view.temporalWorkflow!.phase = 'ORDER_COMPLETION';
    view.temporalWorkflow!.outcome = null;
    expect(fulfillmentProgress(view)).toMatchObject({ state: 'completed', stopTracking: true, message: '履約完成' });
    expect(view.temporalWorkflow).toHaveProperty('updatedAt');
  });
  it.each(['NOT_FOUND', 'UNAVAILABLE'])('%s does not override business progress', status => {
    const view = fixture('temporal');
    view.temporalWorkflow = null;
    view.workflowQueryStatus = status;
    expect(fulfillmentProgress(view)).toMatchObject({ state: 'completed', stopTracking: true });
    view.order.status = 'PENDING';
    view.order.fulfilledByShipmentId = null;
    view.stockOperation = null;
    view.shipments = [];
    expect(fulfillmentProgress(view)).toMatchObject({ state: 'waiting', stopTracking: false });
  });
  it.each(['waiting', 'allocated', 'picking', 'handedOver', 'done', 'complete', 'cancelled', 'unknown'])(
    'both drivers have identical %s progress and polling decisions', stage => {
      const view = fixture('events');
      if (stage !== 'complete') {
        view.order.status = 'ALLOCATED';
        view.order.fulfilledByShipmentId = null;
        view.stockOperation!.operation.state = 'ASSIGNED';
        view.shipments[0]!.status = 'CREATED';
      }
      if (stage === 'waiting') {
        view.order.status = 'PENDING';
        view.stockOperation!.operation.state = 'CONFIRMED';
        view.shipments = [];
      }
      if (stage === 'picking') view.shipments[0]!.status = 'PICKING';
      if (stage === 'handedOver' || stage === 'done') view.shipments[0]!.status = 'HANDED_OVER_TO_CARRIER';
      if (stage === 'done') view.stockOperation!.operation.state = 'DONE';
      if (stage === 'cancelled') view.order.status = 'CANCELLED';
      if (stage === 'unknown') view.order.status = 'FUTURE';
      const temporal = { ...view, orchestrationMode: 'temporal', workflowQueryStatus: 'AVAILABLE',
        temporalWorkflow: fixture('temporal').temporalWorkflow };
      expect(fulfillmentProgress(temporal)).toEqual(fulfillmentProgress(view));
    },
  );
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
