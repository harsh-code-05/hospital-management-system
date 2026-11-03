import assert from 'node:assert/strict';
import test from 'node:test';
import { RequestSequencer, executeSequencedRequest } from '../src/utils/requestSequencer.js';

function defer() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

test('older API response arriving after a newer response does not overwrite data', async () => {
  const sequencer = new RequestSequencer();
  const state = { appointments: [], loading: false, error: '', loaded: false };

  const deferred1 = defer();
  const deferred2 = defer();

  // Request 1 starts (older)
  const req1Promise = executeSequencedRequest(
    sequencer,
    () => deferred1.promise,
    {
      onStart: () => { state.loading = true; state.error = ''; },
      onSuccess: (data) => { state.appointments = data; state.loaded = true; state.loading = false; },
      onError: (err) => { state.error = err.message; state.loading = false; },
    },
  );

  // Request 2 starts (newer)
  const req2Promise = executeSequencedRequest(
    sequencer,
    () => deferred2.promise,
    {
      onStart: () => { state.loading = true; state.error = ''; },
      onSuccess: (data) => { state.appointments = data; state.loaded = true; state.loading = false; },
      onError: (err) => { state.error = err.message; state.loading = false; },
    },
  );

  // Request 2 (newer) finishes first with newer data
  deferred2.resolve([{ id: 2, status: 'CONFIRMED' }]);
  const req2Result = await req2Promise;

  assert.equal(req2Result, true);
  assert.equal(state.loaded, true);
  assert.equal(state.loading, false);
  assert.deepEqual(state.appointments, [{ id: 2, status: 'CONFIRMED' }]);

  // Request 1 (older) finishes later with stale data
  deferred1.resolve([{ id: 1, status: 'CANCELLED' }]);
  const req1Result = await req1Promise;

  assert.equal(req1Result, false);
  // Stale data must NOT have overwritten newer data!
  assert.deepEqual(state.appointments, [{ id: 2, status: 'CONFIRMED' }]);
  assert.equal(state.loading, false);
});

test('older slow request cannot clear loading state while a newer request is still in flight', async () => {
  const sequencer = new RequestSequencer();
  const state = { appointments: [], loading: false, error: '' };

  const deferred1 = defer();
  const deferred2 = defer();

  const req1Promise = executeSequencedRequest(
    sequencer,
    () => deferred1.promise,
    {
      onStart: () => { state.loading = true; },
      onSuccess: (data) => { state.appointments = data; state.loading = false; },
      onError: () => { state.loading = false; },
    },
  );

  const req2Promise = executeSequencedRequest(
    sequencer,
    () => deferred2.promise,
    {
      onStart: () => { state.loading = true; },
      onSuccess: (data) => { state.appointments = data; state.loading = false; },
      onError: () => { state.loading = false; },
    },
  );

  assert.equal(state.loading, true);

  // Request 1 resolves while Request 2 is still pending
  deferred1.resolve([{ id: 1 }]);
  await req1Promise;

  // Request 1 must NOT clear loading state because Request 2 is still in flight
  assert.equal(state.loading, true);
  assert.deepEqual(state.appointments, []);

  // Now Request 2 resolves
  deferred2.resolve([{ id: 2 }]);
  await req2Promise;

  assert.equal(state.loading, false);
  assert.deepEqual(state.appointments, [{ id: 2 }]);
});

test('older failed request cannot overwrite a newer successful response with an error', async () => {
  const sequencer = new RequestSequencer();
  const state = { appointments: [], loading: false, error: '' };

  const deferred1 = defer();
  const deferred2 = defer();

  const req1Promise = executeSequencedRequest(
    sequencer,
    () => deferred1.promise,
    {
      onStart: () => { state.loading = true; state.error = ''; },
      onSuccess: (data) => { state.appointments = data; state.loading = false; },
      onError: (err) => { state.error = err.message; state.loading = false; },
    },
  );

  const req2Promise = executeSequencedRequest(
    sequencer,
    () => deferred2.promise,
    {
      onStart: () => { state.loading = true; state.error = ''; },
      onSuccess: (data) => { state.appointments = data; state.loading = false; },
      onError: (err) => { state.error = err.message; state.loading = false; },
    },
  );

  // Request 2 succeeds
  deferred2.resolve([{ id: 2 }]);
  await req2Promise;
  assert.deepEqual(state.appointments, [{ id: 2 }]);
  assert.equal(state.error, '');

  // Request 1 fails later
  deferred1.reject(new Error('Old request network timeout'));
  await req1Promise;

  // Error from older request must NOT overwrite newer success
  assert.equal(state.error, '');
  assert.deepEqual(state.appointments, [{ id: 2 }]);
});

test('older successful request cannot clear or overwrite an error from a newer request', async () => {
  const sequencer = new RequestSequencer();
  const state = { appointments: [], loading: false, error: '' };

  const deferred1 = defer();
  const deferred2 = defer();

  const req1Promise = executeSequencedRequest(
    sequencer,
    () => deferred1.promise,
    {
      onStart: () => { state.loading = true; state.error = ''; },
      onSuccess: (data) => { state.appointments = data; state.loading = false; },
      onError: (err) => { state.error = err.message; state.loading = false; },
    },
  );

  const req2Promise = executeSequencedRequest(
    sequencer,
    () => deferred2.promise,
    {
      onStart: () => { state.loading = true; state.error = ''; },
      onSuccess: (data) => { state.appointments = data; state.loading = false; },
      onError: (err) => { state.error = err.message; state.loading = false; },
    },
  );

  // Request 2 fails
  deferred2.reject(new Error('Server error on latest refresh'));
  await req2Promise;

  assert.equal(state.error, 'Server error on latest refresh');
  assert.equal(state.loading, false);

  // Request 1 succeeds later
  deferred1.resolve([{ id: 1 }]);
  await req1Promise;

  // Older request must NOT overwrite the newer error state or set old appointments
  assert.equal(state.error, 'Server error on latest refresh');
  assert.deepEqual(state.appointments, []);
});

test('component cleanup on unmount aborts in-flight request and ignores late response', async () => {
  const sequencer = new RequestSequencer();
  let successCalled = false;
  let errorCalled = false;
  let abortedSignalSeen = false;

  const deferred = defer();

  const promise = executeSequencedRequest(
    sequencer,
    (signal) => {
      signal.addEventListener('abort', () => {
        abortedSignalSeen = true;
      });
      return deferred.promise;
    },
    {
      onSuccess: () => { successCalled = true; },
      onError: () => { errorCalled = true; },
    },
  );

  // Simulate component unmount
  sequencer.dispose();
  assert.equal(abortedSignalSeen, true);

  // In-flight promise resolves after unmount
  deferred.resolve([{ id: 99 }]);
  const result = await promise;

  assert.equal(result, false);
  assert.equal(successCalled, false);
  assert.equal(errorCalled, false);
});

test('aborted request error is ignored and does not trigger onError callback', async () => {
  const sequencer = new RequestSequencer();
  let errorCalled = false;

  const deferred1 = defer();
  const deferred2 = defer();

  const req1Promise = executeSequencedRequest(
    sequencer,
    () => deferred1.promise,
    {
      onError: () => { errorCalled = true; },
    },
  );

  // Starting request 2 aborts request 1
  executeSequencedRequest(sequencer, () => deferred2.promise);

  // Request 1 rejects with CanceledError
  const cancelErr = new Error('canceled');
  cancelErr.name = 'CanceledError';
  deferred1.reject(cancelErr);

  const res1 = await req1Promise;
  assert.equal(res1, false);
  assert.equal(errorCalled, false);

  deferred2.resolve([]);
});
