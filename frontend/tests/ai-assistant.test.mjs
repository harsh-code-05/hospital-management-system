import assert from 'node:assert/strict';
import test from 'node:test';
import { createAssistantService, mergeMessages, messageRequest } from '../src/api/aiAssistantService.js';
import { createApiClient } from '../src/api/axios.js';

const root = '/patient/assistant/sessions';
const request = { requestId: '00000000-0000-0000-0000-000000000001', content: 'Portal help' };
const message = { id: 1, requestId: request.requestId, role: 'USER', content: request.content, createdAt: '2026-11-05T12:00:00' };
const reply = { ...message, id: 2, role: 'ASSISTANT', content: 'Use the portal tabs.' };
const session = { id: 1, title: 'New conversation', createdAt: message.createdAt, updatedAt: message.createdAt };
const json = (data) => ({ status: 200, headers: { 'content-type': 'application/json' }, data });

test('assistant APIs reject HTML and malformed successful responses', async () => {
  for (const response of [
    { status: 200, headers: { 'content-type': 'text/html' }, data: '<html>Login</html>' },
    json({ unexpected: true }),
  ]) {
    const service = createAssistantService({ get: async () => response, post: async () => response, delete: async () => response });
    await assert.rejects(service.sessions(), /unexpected response/);
    await assert.rejects(service.create(), /unexpected response/);
    await assert.rejects(service.messages(1), /unexpected response/);
    await assert.rejects(service.send(1, request), /unexpected response/);
    await assert.rejects(service.remove(1), /unexpected response/);
  }
});

test('assistant requires a complete reply pair for the submitted persisted request ID', async () => {
  for (const messages of [[message], [reply, message], [message, { ...reply, requestId: 'wrong' }], [message, { ...reply, content: '' }]]) {
    const service = createAssistantService({ post: async () => json({ messages }) });
    await assert.rejects(service.send(1, request), /unexpected response/);
  }
  const service = createAssistantService({ post: async () => json({ messages: [message, reply] }) });
  assert.deepEqual(await service.send(1, request), [message, reply]);
});

test('manual retry reuses its request ID only for the same normalized content', () => {
  const original = messageRequest(null, '  Portal help  ', () => request.requestId);
  assert.deepEqual(original, request);
  assert.equal(messageRequest(original, 'Portal help', () => assert.fail('unexpected ID rotation')), original);
  assert.equal(messageRequest(original, 'Different message', () => 'next').requestId, 'next');
  assert.deepEqual(mergeMessages([message, reply], [message, reply]), [message, reply]);
});

test('create, send and delete use existing cookie/CSRF client and never send a patient ID', async () => {
  const requests = [];
  const api = createApiClient(async (config) => {
    requests.push(config);
    if (config.url === '/auth/csrf') return json({ headerName: 'X-CSRF-TOKEN', token: 'test-csrf' });
    if (config.method === 'delete') return { status: 204, data: '' };
    if (config.url.endsWith('/messages')) return json({ messages: [message, reply] });
    return json(session);
  });
  const service = createAssistantService(api);
  await service.create();
  await service.send(1, request);
  await service.remove(1);
  const mutations = requests.filter((r) => r.method !== 'get');
  assert.equal(mutations.length, 3);
  assert.deepEqual(mutations.map((r) => r.url), [root, `${root}/1/messages`, `${root}/1`]);
  for (const call of mutations) {
    assert.equal(call.withCredentials, true);
    assert.equal(call.headers.get('X-CSRF-TOKEN'), 'test-csrf');
    assert.ok(!call.data || !Object.hasOwn(JSON.parse(call.data), 'patientId'));
  }
  assert.deepEqual(JSON.parse(mutations[1].data), request);
  assert.equal(mutations[1].timeout, 10000);
});

test('assistant send omits legacy context flags and untrusted extra fields', async () => {
  let submitted;
  const service = createAssistantService({ post: async (_path, body) => {
    submitted = body;
    return json({ messages: [message, reply] });
  } });
  await service.send(1, { ...request, includeAppointments: true, patientId: 99 });
  assert.deepEqual(submitted, request);
});

test('an expired assistant send expires the central session and is never replayed', async () => {
  let sends = 0;
  let expired = 0;
  const api = createApiClient(async (config) => {
    if (config.url === '/auth/csrf') return json({ headerName: 'X-CSRF-TOKEN', token: 'test-csrf' });
    sends++;
    throw Object.assign(new Error('HTTP 401'), { config, response: { status: 401 } });
  });
  api.startSession();
  api.onSessionExpired(() => { expired++; });
  await assert.rejects(createAssistantService(api).send(1, request), /401/);
  assert.equal(sends, 1);
  assert.equal(expired, 1);
});
