import { createElement, Fragment } from 'react';

// Distinguish initial loading and API failures from genuine metric values,
// and indicate stale data on refresh failure while preserving cached metrics.
export default function AppointmentMetricsState({
  loading,
  error,
  hasLoaded,
  metrics,
  loadingMessage = 'Loading appointment metrics...',
  children,
}) {
  if (!hasLoaded) {
    if (error && !loading) {
      return createElement(
        'div',
        { className: 'dashboard-alert alert-error', role: 'alert' },
        error,
      );
    }
    return createElement(
      'div',
      { className: 'empty-placeholder', role: 'status' },
      createElement('p', null, loadingMessage),
    );
  }

  const content = children || (metrics && createElement(
    'div',
    { className: 'metrics-row' },
    createElement(
      'div',
      { className: 'metric-tile' },
      createElement('span', { className: 'metric-label' }, 'Total Appointments'),
      createElement('span', { className: 'metric-val' }, metrics.total ?? 0),
    ),
    createElement(
      'div',
      { className: 'metric-tile' },
      createElement('span', { className: 'metric-label' }, 'Confirmed'),
      createElement(
        'span',
        { className: 'metric-val', style: { color: '#16a34a' } },
        metrics.confirmed ?? 0,
      ),
    ),
    createElement(
      'div',
      { className: 'metric-tile' },
      createElement('span', { className: 'metric-label' }, 'Completed'),
      createElement(
        'span',
        { className: 'metric-val', style: { color: '#2563eb' } },
        metrics.completed ?? 0,
      ),
    ),
    createElement(
      'div',
      { className: 'metric-tile' },
      createElement('span', { className: 'metric-label' }, 'Cancelled'),
      createElement(
        'span',
        { className: 'metric-val', style: { color: '#dc2626' } },
        metrics.cancelled ?? 0,
      ),
    ),
    createElement(
      'div',
      { className: 'metric-tile' },
      createElement('span', { className: 'metric-label' }, 'No Show'),
      createElement(
        'span',
        { className: 'metric-val', style: { color: '#d97706' } },
        metrics.noShow ?? 0,
      ),
    ),
  ));

  return createElement(
    Fragment,
    null,
    loading && createElement(
      'div',
      { className: 'dashboard-alert alert-info', role: 'status' },
      'Refreshing metrics. Showing the last successfully loaded data.',
    ),
    error && createElement(
      'div',
      { className: 'dashboard-alert alert-warning', role: 'alert' },
      `${error} The displayed metric data may be stale. Refresh the list to try again.`,
    ),
    content,
  );
}
