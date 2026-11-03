import assert from 'node:assert/strict';
import test from 'node:test';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import AppointmentMetricsState from '../src/components/AppointmentMetricsState.js';

const defaultMetrics = {
  total: 5,
  confirmed: 2,
  completed: 1,
  cancelled: 1,
  noShow: 1,
};

const defaults = {
  loading: false,
  error: '',
  hasLoaded: true,
  metrics: defaultMetrics,
  loadingMessage: 'Loading appointment metrics...',
};

function render(overrides = {}) {
  return renderToStaticMarkup(createElement(
    AppointmentMetricsState,
    { ...defaults, ...overrides },
  ));
}

test('initial loading displays a clear loading state and no unqualified zero metrics', () => {
  const html = render({
    loading: true,
    hasLoaded: false,
    metrics: { total: 0, confirmed: 0, completed: 0, cancelled: 0, noShow: 0 },
  });
  assert.match(html, /role="status"/);
  assert.match(html, /Loading appointment metrics/);
  assert.doesNotMatch(html, /metric-val/);
  assert.doesNotMatch(html, /role="alert"/);
});

test('initial API failure displays an appropriate error state and no false empty state or zero metrics', () => {
  const html = render({
    hasLoaded: false,
    error: 'Failed to load appointments',
    loading: false,
    metrics: { total: 0, confirmed: 0, completed: 0, cancelled: 0, noShow: 0 },
  });
  assert.match(html, /role="alert"/);
  assert.match(html, /Failed to load appointments/);
  assert.doesNotMatch(html, /metric-val|may be stale|No appointments/);
});

test('successful empty appointment response displays genuine zero counts without error or stale warnings', () => {
  const html = render({
    hasLoaded: true,
    loading: false,
    error: '',
    metrics: { total: 0, confirmed: 0, completed: 0, cancelled: 0, noShow: 0 },
  });
  assert.match(html, /Total Appointments/);
  assert.match(html, /<span class="metric-val">0<\/span>/);
  assert.doesNotMatch(html, /role="alert"|may be stale|Refreshing/);
});

test('successful populated appointment response displays actual metric counts', () => {
  const html = render({
    hasLoaded: true,
    loading: false,
    error: '',
    metrics: { total: 12, confirmed: 6, completed: 3, cancelled: 2, noShow: 1 },
  });
  assert.match(html, /<span class="metric-val">12<\/span>/);
  assert.match(html, /<span class="metric-val"[^>]*>6<\/span>/);
  assert.match(html, /<span class="metric-val"[^>]*>3<\/span>/);
  assert.match(html, /<span class="metric-val"[^>]*>2<\/span>/);
  assert.match(html, /<span class="metric-val"[^>]*>1<\/span>/);
  assert.doesNotMatch(html, /role="alert"|may be stale|Refreshing/);
});

test('refresh in progress retains cached metrics and labels them', () => {
  const html = render({ loading: true, hasLoaded: true });
  assert.match(html, /Refreshing metrics/);
  assert.match(html, /last successfully loaded data/);
  assert.match(html, /<span class="metric-val">5<\/span>/);
});

test('refresh failure preserves previously loaded metrics and clearly indicates data may be stale', () => {
  const html = render({ error: 'Refresh failed', hasLoaded: true });
  assert.match(html, /role="alert"/);
  assert.match(html, /Refresh failed/);
  assert.match(html, /displayed metric data may be stale/);
  assert.match(html, /<span class="metric-val">5<\/span>/);
});

test('successful retry clears stale and error indicators and displays fresh metrics', () => {
  const failed = render({ error: 'Refresh failed', hasLoaded: true });
  assert.match(failed, /Refresh failed/);
  assert.match(failed, /displayed metric data may be stale/);

  const recovered = render({
    hasLoaded: true,
    error: '',
    loading: false,
    metrics: { total: 7, confirmed: 3, completed: 2, cancelled: 1, noShow: 1 },
  });
  assert.match(recovered, /<span class="metric-val">7<\/span>/);
  assert.doesNotMatch(recovered, /role="alert"|Refresh failed|may be stale/);
});

test('renders children directly when provided after data has loaded', () => {
  const html = renderToStaticMarkup(createElement(
    AppointmentMetricsState,
    { hasLoaded: true, loading: false, error: '' },
    createElement('div', { className: 'custom-metrics' }, 'Custom Metrics 99'),
  ));
  assert.match(html, /Custom Metrics 99/);
});
