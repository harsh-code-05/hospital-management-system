import { Navigate } from 'react-router-dom';
import { useAuth } from '../context/useAuth';

/**
 * Normalizes a backend role string by stripping the "ROLE_" prefix
 * so that both "ROLE_PATIENT" and "PATIENT" match uniformly.
 */
function normalizeRole(role) {
  if (typeof role !== 'string') return '';
  return role.replace(/^ROLE_/i, '').toUpperCase();
}

/**
 * Route guard component.
 *
 * Props:
 *   allowedRoles – optional array of role strings (e.g. ["ADMIN", "DOCTOR"]).
 *                  When omitted, any authenticated user may access the route.
 *   children     – the protected content to render.
 */
export default function ProtectedRoute({ allowedRoles, children }) {
  const { user, loading } = useAuth();

  if (loading) {
    return <div>Loading…</div>;
  }

  if (!user) {
    return <Navigate to="/login" replace />;
  }

  if (allowedRoles && allowedRoles.length > 0) {
    const userRole = normalizeRole(user.role);
    const permitted = allowedRoles.some(
      (role) => normalizeRole(role) === userRole,
    );

    if (!permitted) {
      return <Navigate to="/unauthorized" replace />;
    }
  }

  return children;
}
