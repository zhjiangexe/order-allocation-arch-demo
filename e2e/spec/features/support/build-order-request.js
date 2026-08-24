function fn(input) {
  return {
    ownerId: input.ownerId,
    externalOrderNo: input.externalOrderNo,
    shipToZone: '100',
    shipToAddress: '台北市中正區 Karate 測試路 1 號',
    promisedDeliveryDate: '2099-01-02',
    dispatchBy: '2099-01-01T12:00:00Z',
    releasePriority: 50,
    facilityId: input.facilityId,
    lines: input.lines
  };
}
