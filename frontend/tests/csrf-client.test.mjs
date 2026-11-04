import assert from 'node:assert/strict';
import test from 'node:test';
import { createApiClient } from '../src/api/axios.js';

test('API mutations obtain a session token while reads need no token', async () => {
  const requests = [];
  let tokenVersion = 0;
  const client = createApiClient(async (request) => {
    requests.push([request.url, request.headers.get('X-CSRF-TOKEN')]);
    const data = request.url === '/auth/csrf'
      ? { headerName: 'X-CSRF-TOKEN', token: `token-${++tokenVersion}` }
      : {};
    return { status: 200, data };
  });

  await client.get('/auth/me');
  await client.post('/auth/login', {});
  await client.patch('/appointments/42/cancel');
  await client.post('/auth/logout');

  assert.deepEqual(requests, [
    ['/auth/me', undefined],
    ['/auth/csrf', undefined],
    ['/auth/login', 'token-1'],
    ['/auth/csrf', undefined],
    ['/appointments/42/cancel', 'token-2'],
    ['/auth/csrf', undefined],
    ['/auth/logout', 'token-3'],
  ]);
});

test('a failed token bootstrap prevents sending the mutation', async () => {
  const requests = [];
  const client = createApiClient(async (request) => {
    requests.push(request.url);
    return { status: 200, data: { headerName: 'X-CSRF-TOKEN' } };
  });

  await assert.rejects(client.post('/auth/logout'), /Unable to obtain a CSRF token/);
  assert.deepEqual(requests, ['/auth/csrf']);
});
