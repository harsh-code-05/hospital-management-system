
import axios from 'axios';

export function createApiClient(adapter) {
  const client = axios.create({
    baseURL: '/api',
    allowAbsoluteUrls: false,
    withCredentials: true,
    headers: { 'Content-Type': 'application/json' },
    adapter,
  });

  let tokenRequest;
  let session;
  const expiryListeners = new Set();

  client.startSession = () => { session = Symbol(); };
  client.clearSession = () => { session = undefined; };
  client.onSessionExpired = (listener) => {
    expiryListeners.add(listener);
    return () => { expiryListeners.delete(listener); };
  };

  client.interceptors.request.use(async (config) => {
    config.appSession = session;
    if (['get', 'head', 'options', 'trace'].includes(config.method?.toLowerCase())) {
      return config;
    }

    // Share concurrent bootstraps, but fetch again after session changes.
    tokenRequest ??= client.get('/auth/csrf').finally(() => {
      tokenRequest = undefined;
    });
    const { data } = await tokenRequest;
    if (config.appSession && config.appSession !== session) {
      throw new Error('Your session changed. Please sign in and try again.');
    }
    if (data?.headerName !== 'X-CSRF-TOKEN' || typeof data.token !== 'string' || !data.token) {
      throw new Error('Unable to obtain a CSRF token.');
    }
    config.headers.set(data.headerName, data.token);
    return config;
  });

  client.interceptors.response.use(undefined, (error) => {
    if (error.response?.status === 401 && error.config?.url !== '/auth/login'
        && session && error.config?.appSession === session) {
      client.clearSession();
      expiryListeners.forEach((listener) => listener());
    }
    return Promise.reject(error);
  });

  return client;
}

export default createApiClient();
