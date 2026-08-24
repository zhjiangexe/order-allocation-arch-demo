function fn() {
  var System = Java.type('java.lang.System');
  var UUID = Java.type('java.util.UUID');
  var baseUrl = System.getenv('E2E_BASE_URL') || 'http://localhost:28290';
  var connectUrl = System.getenv('E2E_CONNECT_URL') || 'http://localhost:28293';
  var connectorName = System.getenv('E2E_CONNECTOR_NAME') || 'archone-karate-e2e-outbox';
  var ownerId = '00000000-0000-0000-0000-000000000001';
  var facilityId = '00000000-0000-0000-0000-000000000011';
  var locationId = '00000000-0000-0000-0000-000000000021';

  karate.configure('connectTimeout', 5000);
  karate.configure('readTimeout', 10000);

  return {
    baseUrl: baseUrl,
    connectUrl: connectUrl,
    connectorName: connectorName,
    ownerId: ownerId,
    facilityId: facilityId,
    locationId: locationId,
    newId: function() {
      return UUID.randomUUID().toString();
    },
    now: function() {
      return new Date().toISOString();
    }
  };
}
