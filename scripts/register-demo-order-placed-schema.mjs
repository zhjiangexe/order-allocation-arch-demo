import { readFileSync } from 'node:fs';
import { isDeepStrictEqual } from 'node:util';

const document = JSON.parse(readFileSync('/demo/order-placed-v1.json', 'utf8'));
const message = document.components.messages.orderPlaced;
const subject = `${document.channels.orderEvents.address}.${message.name}-value`;
const registryUrl = process.env.REGISTRY_URL;

if (!registryUrl) {
  throw new Error('REGISTRY_URL is required');
}

const endpoint = `${registryUrl}/subjects/${encodeURIComponent(subject)}/versions`;
const latest = await fetch(`${endpoint}/latest`);

if (latest.ok) {
  const current = await latest.json();
  if (current.schemaType !== 'JSON' || !isDeepStrictEqual(JSON.parse(current.schema), message.payload)) {
    throw new Error(`Demo schema subject ${subject} already exists with different content`);
  }
  console.log(`Demo JSON Schema subject ${subject} already matches (version ${current.version})`);
  process.exit(0);
}

if (latest.status !== 404) {
  throw new Error(`Cannot inspect demo schema subject (${latest.status}): ${await latest.text()}`);
}

const response = await fetch(endpoint, {
  method: 'POST',
  headers: { 'Content-Type': 'application/vnd.schemaregistry.v1+json' },
  body: JSON.stringify({ schemaType: 'JSON', schema: JSON.stringify(message.payload) }),
});

if (!response.ok) {
  throw new Error(`Schema registration failed (${response.status}): ${await response.text()}`);
}

const { id } = await response.json();
console.log(`Registered demo JSON Schema subject ${subject} (id ${id})`);
