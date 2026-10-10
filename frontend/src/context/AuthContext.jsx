import { useEffect, useState } from 'react';
import api from '../api/axios';
import { AuthContext } from './useAuth';

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(true);

  async function refreshUser() {
    try {
      const response = await api.get('/auth/me');
      setUser(response.data);
      return response.data;
    } catch (error) {
      if (error.response?.status === 401) {
        setUser(null);
        return null;
      }

      throw error;
    }
  }

  async function logout() {
    await api.post('/auth/logout');
    setUser(null);
  }

  useEffect(() => {
    let ignore = false;

    async function checkSession() {
      try {
        const response = await api.get('/auth/me');
        if (!ignore) {
          setUser(response.data);
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
    };
  }, []);

  return (
    <AuthContext.Provider
      value={{ user, loading, refreshUser, logout }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export default AuthProvider;
