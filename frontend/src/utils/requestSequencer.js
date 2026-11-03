// Manages request sequencing, cancellation, and component lifecycle safety
// to prevent older or aborted API responses from overwriting newer state.
export class RequestSequencer {
  constructor() {
    this.currentId = 0;
    this.activeController = null;
    this.isMounted = true;
  }

  mount() {
    this.isMounted = true;
  }

  startRequest() {
    this.isMounted = true;
    if (this.activeController) {
      this.activeController.abort();
    }
    this.activeController = typeof AbortController !== 'undefined' ? new AbortController() : null;
    this.currentId += 1;
    const requestId = this.currentId;

    return {
      requestId,
      signal: this.activeController ? this.activeController.signal : undefined,
      isLatest: () => this.isMounted && this.currentId === requestId,
    };
  }

  isLatest(requestId) {
    return this.isMounted && this.currentId === requestId;
  }

  cancelActive() {
    if (this.activeController) {
      this.activeController.abort();
      this.activeController = null;
    }
  }

  dispose() {
    this.isMounted = false;
    this.cancelActive();
  }
}

export function executeSequencedRequest(sequencer, fetcher, { onStart, onSuccess, onError } = {}) {
  const request = sequencer.startRequest();
  if (!request) return Promise.resolve(false);

  if (onStart) onStart();

  return fetcher(request.signal)
    .then((result) => {
      if (!request.isLatest() || request.signal?.aborted) {
        return false;
      }
      if (onSuccess) onSuccess(result);
      return true;
    })
    .catch((err) => {
      if (!request.isLatest() || request.signal?.aborted || err?.name === 'CanceledError') {
        return false;
      }
      if (onError) onError(err);
      return false;
    });
}
