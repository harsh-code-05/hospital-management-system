import assert from 'node:assert/strict';
import test from 'node:test';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import AppointmentListState from '../src/components/AppointmentListState.js';

const defaults = {
  loading: false,
  error: '',
  hasLoaded: true,
  isEmpty: false,
  loadingMessage: 'Loading appointments...',
  emptyMessage: 'No appointments match the current filters.',
};

function render(overrides = {}) {
  return renderToStaticMarkup(createElement(
    AppointmentListState,
    { ...defaults, ...overrides },
    createElement('article', null, 'Appointment #16 CONFIRMED'),
  ));
}

test('initial loading never displays cached or empty results', () => {
  const html = render({ loading: true, hasLoaded: false, isEmpty: true });
  assert.match(html, /role="status"/);
  assert.match(html, /Loading appointments/);
  assert.doesNotMatch(html, /No appointments|Appointment #16/);
});

test('initial fetch failure displays only an error', () => {
  const html = render({ hasLoaded: false, error: 'Request failed', isEmpty: true });
  assert.match(html, /role="alert"/);
  assert.match(html, /Request failed/);
  assert.doesNotMatch(html, /No appointments|Appointment #16|may be stale/);
});

test('successful empty response displays the current filter empty message', () => {
  const html = render({ isEmpty: true });
  assert.match(html, /No appointments found/);
  assert.match(html, /No appointments match the current filters/);
  assert.doesNotMatch(html, /role="alert"|may be stale|Appointment #16/);
});

test('successful populated response displays appointment rows', () => {
  const html = render();
  assert.match(html, /Appointment #16 CONFIRMED/);
  assert.doesNotMatch(html, /role="alert"|No appointments|Refreshing/);
});

test('refresh in progress retains cached rows and labels them', () => {
  const html = render({ loading: true });
  assert.match(html, /Refreshing appointments/);
  assert.match(html, /last successfully loaded data/);
  assert.match(html, /Appointment #16 CONFIRMED/);
});

test('refresh failure keeps cached rows with an explicit stale warning', () => {
  const html = render({ error: 'Refresh failed' });
  assert.match(html, /role="alert"/);
  assert.match(html, /Refresh failed/);
  assert.match(html, /displayed appointment data may be stale/);
  assert.match(html, /Appointment #16 CONFIRMED/);
});

test('refresh failure after an empty response does not claim fresh empty results', () => {
  const html = render({ isEmpty: true, error: 'Refresh failed' });
  assert.match(html, /displayed appointment data may be stale/);
  assert.match(html, /No appointments in the last loaded results/);
  assert.doesNotMatch(html, /No appointments found|current filters/);
});

test('refreshing cached empty results keeps the last-loaded qualification', () => {
  const html = render({ isEmpty: true, loading: true });
  assert.match(html, /Refreshing appointments/);
  assert.match(html, /No appointments in the last loaded results/);
  assert.doesNotMatch(html, /No appointments found|current filters/);
});

test('successful retry replaces the error state with current results', () => {
  const failed = render({ hasLoaded: false, error: 'Request failed' });
  assert.match(failed, /Request failed/);
  const recovered = render({ hasLoaded: true, error: '', loading: false });
  assert.match(recovered, /Appointment #16 CONFIRMED/);
  assert.doesNotMatch(recovered, /Request failed|may be stale|role="alert"/);
});
