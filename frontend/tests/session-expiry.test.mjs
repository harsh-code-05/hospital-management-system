import assert from 'node:assert/strict';
import test from 'node:test';
import { createApiClient } from '../src/api/axios.js';
import { createFormDrafts } from '../src/context/formDrafts.js';

function rejectedResponse(config, status) {
  return Object.assign(new Error(`HTTP ${status}`), { config, response: { status } });
}

test('expired mutation notifies the auth provider and is sent only once', async () => {
  const calls = [];
  const client = createApiClient(async (config) => {
    calls.push(config.url);
    if (config.url === '/auth/csrf') {
      return { data: { headerName: 'X-CSRF-TOKEN', token: 'fixture' } };
    }
    throw rejectedResponse(config, 401);
  });
  let expired = 0;
  client.onSessionExpired(() => { expired++; });
  client.startSession();

  await assert.rejects(client.post('/appointments', {}), /HTTP 401/);
  assert.equal(expired, 1);
  assert.deepEqual(calls, ['/auth/csrf', '/appointments']);
});

test('late 401 from an older session cannot expire a newly signed-in session', async () => {
  let rejectOldRequest;
  let started;
  const ready = new Promise((resolve) => { started = resolve; });
  const client = createApiClient((config) => new Promise((_, reject) => {
    rejectOldRequest = () => reject(rejectedResponse(config, 401));
    started();
  }));
  let expired = 0;
  client.onSessionExpired(() => { expired++; });
  client.startSession();
  const original = client.get('/appointments');
  await ready;
  client.startSession();
  rejectOldRequest();

  await assert.rejects(original, /HTTP 401/);
  assert.equal(expired, 0);
});

test('a protected GET 401 expires the session once while 403 does not', async () => {
  const client = createApiClient(async (config) => {
    throw rejectedResponse(config, config.url === '/admin' ? 403 : 401);
  });
  let expired = 0;
  client.onSessionExpired(() => { expired++; });
  client.startSession();

  await assert.rejects(client.get('/admin'), /HTTP 403/);
  assert.equal(expired, 0);
  await assert.rejects(client.get('/appointments'), /HTTP 401/);
  await assert.rejects(client.get('/appointments'), /HTTP 401/);
  assert.equal(expired, 1);
});

test('a submitted draft survives same-account sign-in and is discarded on account switch', () => {
  const drafts = createFormDrafts();
  const booking = { selectedDate: '2099-01-01', bookingReason: 'Fixture reason' };
  drafts.selectOwner(41);
  drafts.save(41, 'booking', booking);
  assert.equal(drafts.get(undefined, 'booking'), undefined);
  drafts.selectOwner(41);
  assert.equal(drafts.get(41, 'booking'), booking);
  drafts.selectOwner(42);
  assert.equal(drafts.get(42, 'booking'), undefined);
  drafts.selectOwner(41);
  assert.equal(drafts.get(41, 'booking'), undefined);
});
