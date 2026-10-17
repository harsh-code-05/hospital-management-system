import { useState } from 'react';
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router-dom';
import api from '../api/axios';
import { useAuth } from '../context/useAuth';
import './LoginPage.css';

/**
 * Normalizes backend role string (e.g. "ROLE_PATIENT" or "PATIENT")
 * and maps it to the corresponding dashboard path.
 */
function getDashboardPath(role) {
  if (!role || typeof role !== 'string') return '/unauthorized';
  const normalized = role.replace(/^ROLE_/i, '').toUpperCase();
  switch (normalized) {
    case 'PATIENT':
      return '/patient';
    case 'DOCTOR':
      return '/doctor';
    case 'ADMIN':
      return '/admin';
    default:
      return '/unauthorized';
  }
}

export default function LoginPage() {
  const { user, loading, refreshUser } = useAuth();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [errorMessage, setErrorMessage] = useState(() => {
    return searchParams.get('error')
      ? 'Google authentication failed. Please try again.'
      : '';
  });
  const [isSubmitting, setIsSubmitting] = useState(false);

  // If already authenticated and done loading, redirect to role dashboard
  if (!loading && user) {
    return <Navigate to={getDashboardPath(user.role)} replace />;
  }

  const handleSubmit = async (e) => {
    e.preventDefault();
    setErrorMessage('');

    const trimmedEmail = email.trim();
    if (!trimmedEmail) {
      setErrorMessage('Email is required.');
      return;
    }

    if (!password) {
      setErrorMessage('Password is required.');
      return;
    }

    setIsSubmitting(true);

    try {
      await api.post('/auth/login', {
        email: trimmedEmail,
        password,
      });

      let refreshedUser;
      try {
        refreshedUser = await refreshUser();
      } catch {
        setErrorMessage(
          'Login succeeded, but failed to load user session. Please refresh or try again.',
        );
        setIsSubmitting(false);
        return;
      }

      if (refreshedUser && refreshedUser.role) {
        navigate(getDashboardPath(refreshedUser.role), { replace: true });
      } else {
        setErrorMessage(
          'Unable to verify user role after login. Please try again.',
        );
        setIsSubmitting(false);
      }
    } catch (err) {
      setIsSubmitting(false);

      if (err.response?.status === 401) {
        setErrorMessage(
          err.response?.data?.message || 'Invalid email or password.',
        );
      } else if (err.response?.status === 400) {
        if (err.response?.data?.errors) {
          const fieldErrors = Object.values(err.response.data.errors).join(
            ', ',
          );
          setErrorMessage(
            fieldErrors ||
              err.response?.data?.message ||
              'Validation failed. Please check your input.',
          );
        } else {
          setErrorMessage(
            err.response?.data?.message ||
              'Invalid request. Please check your input.',
          );
        }
      } else {
        setErrorMessage(
          err.response?.data?.message ||
            'Unable to connect to the server. Please try again later.',
        );
      }
    }
  };

  if (loading) {
    return (
      <div className="login-page">
        <div className="login-card" style={{ textAlign: 'center' }}>
          <p>Loading...</p>
        </div>
      </div>
    );
  }

  return (
    <div className="login-page">
      <div className="login-card">
        <div className="login-header">
          <h1>Sign In</h1>
          <p>Access your Hospital Management account</p>
        </div>

        {errorMessage && (
          <div className="login-error" role="alert">
            {errorMessage}
          </div>
        )}

        <form className="login-form" onSubmit={handleSubmit} noValidate>
          <div className="form-group">
            <label htmlFor="email">Email</label>
            <input
              id="email"
              type="email"
              className="form-input"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="name@example.com"
              disabled={isSubmitting}
              autoComplete="email"
              required
            />
          </div>

          <div className="form-group">
            <label htmlFor="password">Password</label>
            <div className="password-input-wrapper">
              <input
                id="password"
                type={showPassword ? 'text' : 'password'}
                className="form-input"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="Enter your password"
                disabled={isSubmitting}
                autoComplete="current-password"
                required
              />
              <button
                type="button"
                className="password-toggle-btn"
                onClick={() => setShowPassword((prev) => !prev)}
                disabled={isSubmitting}
                aria-label={showPassword ? 'Hide password' : 'Show password'}
              >
                {showPassword ? 'Hide' : 'Show'}
              </button>
            </div>
          </div>

          <button
            type="submit"
            className="login-submit-btn"
            disabled={isSubmitting}
          >
            {isSubmitting ? 'Signing in...' : 'Sign In'}
          </button>
        </form>

        <div className="login-divider">
          <span>or</span>
        </div>

        <a href="/oauth2/authorization/google" className="google-login-btn">
          <svg
            className="google-icon"
            viewBox="0 0 24 24"
            aria-hidden="true"
            focusable="false"
          >
            <path
              fill="#4285F4"
              d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z"
            />
            <path
              fill="#34A853"
              d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"
            />
            <path
              fill="#FBBC05"
              d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.06H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.94l2.85-2.22.81-.63z"
            />
            <path
              fill="#EA4335"
              d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.06l3.66 2.84c.87-2.6 3.3-4.52 6.16-4.52z"
            />
          </svg>
          <span>Sign in with Google</span>
        </a>

        <div className="login-footer">
          <Link to="/" className="back-home-link">
            ← Back to Home
          </Link>
        </div>
      </div>
    </div>
  );
}
