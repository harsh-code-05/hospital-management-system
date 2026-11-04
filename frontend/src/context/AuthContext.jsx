import { useCallback, useEffect, useState } from 'react';
import api from '../api/axios';
import { isApiId, requireJsonResponse, UnexpectedApiResponse } from '../api/responseValidation';
import { AuthContext } from './useAuth';
import { createFormDrafts } from './formDrafts';

function readSession(response) {
  return requireJsonResponse(response, (data) => isApiId(data?.id)
    && typeof data.email === 'string' && ['ROLE_ADMIN', 'ROLE_DOCTOR', 'ROLE_PATIENT'].includes(data.role));
}

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(true);
  const [sessionExpired, setSessionExpired] = useState(false);
  const [formDrafts] = useState(createFormDrafts);

  const acceptSession = useCallback((nextUser) => {
    formDrafts.selectOwner(nextUser.id);
    api.startSession();
    setUser(nextUser);
    setSessionExpired(false);
  }, [formDrafts]);

  async function refreshUser() {
    try {
      const nextUser = readSession(await api.get('/auth/me'));
      acceptSession(nextUser);
      return nextUser;
    } catch (error) {
      if (error.response?.status === 401) {
        api.clearSession();
        setUser(null);
        return null;
      }

      throw error;
    }
  }

  async function logout() {
    const response = await api.post('/auth/logout');
    if (response.status !== 204) throw new UnexpectedApiResponse();
    api.clearSession();
    formDrafts.selectOwner(null);
    setSessionExpired(false);
    setUser(null);
  }

  useEffect(() => {
    let ignore = false;
    const unsubscribe = api.onSessionExpired(() => {
      setUser(null);
      setSessionExpired(true);
    });

    async function checkSession() {
      try {
        const nextUser = readSession(await api.get('/auth/me'));
        if (!ignore) {
          acceptSession(nextUser);
        }
      } catch (error) {
        if (!ignore) {
          if (error.response?.status === 401) {
            setUser(null);
          } else {
            console.error('Unable to check login session:', error);
          }
        }
      } finally {
        if (!ignore) {
          setLoading(false);
        }
      }
    }

    checkSession();

    return () => {
      ignore = true;
      unsubscribe();
    };
  }, [acceptSession]);

  return (
    <AuthContext.Provider
      value={{ user, loading, sessionExpired, refreshUser, logout, formDrafts }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export default AuthProvider;
