import { createElement, Fragment } from 'react';

// Both dashboards distinguish initial failures from stale cached results.
export default function AppointmentListState({
  loading,
  error,
  hasLoaded,
  isEmpty,
  loadingMessage,
  emptyMessage,
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

  return createElement(
    Fragment,
    null,
    loading && createElement(
      'div',
      { className: 'dashboard-alert alert-info', role: 'status' },
      'Refreshing appointments. Showing the last successfully loaded data.',
    ),
    error && createElement(
      'div',
      { className: 'dashboard-alert alert-warning', role: 'alert' },
      `${error} The displayed appointment data may be stale. Refresh the list to try again.`,
    ),
    isEmpty
      ? createElement(
        'div',
        { className: 'empty-placeholder' },
        createElement('h3', null, error || loading
          ? 'No appointments in the last loaded results'
          : 'No appointments found'),
        createElement('p', null, error || loading
          ? 'No appointments match the filters in the last successfully loaded data.'
          : emptyMessage),
      )
      : children,
  );
}
