import { Link } from 'react-router-dom';
import { useAuth } from '../context/useAuth';

export default function HomePage() {
  const { user, loading } = useAuth();

  const getDashboardPath = (role) => {
    if (!role || typeof role !== 'string') return '/patient';
    const normalized = role.replace(/^ROLE_/i, '').toUpperCase();
    if (normalized === 'DOCTOR') return '/doctor';
    if (normalized === 'ADMIN') return '/admin';
    return '/patient';
  };

  return (
    <div style={{ padding: '60px 24px', maxWidth: '640px', margin: '0 auto', textAlign: 'center' }}>
      <h1 style={{ fontSize: '36px', marginBottom: '12px' }}>Hospital Management System</h1>
      <p style={{ color: 'var(--text)', fontSize: '16px', marginBottom: '32px' }}>
        Welcome to the Hospital Management System portal.
      </p>

      {!loading && user ? (
        <div style={{ marginBottom: '32px' }}>
          <p style={{ marginBottom: '16px', color: 'var(--text-h)' }}>
            Signed in as <strong>{user.email}</strong> ({user.role})
          </p>
          <Link
            to={getDashboardPath(user.role)}
            style={{
              display: 'inline-block',
              padding: '12px 24px',
              background: 'var(--accent)',
              color: '#fff',
              borderRadius: '8px',
              textDecoration: 'none',
              fontWeight: 600,
            }}
          >
            Go to Dashboard
          </Link>
        </div>
      ) : (
        <div style={{ marginBottom: '32px' }}>
          <Link
            to="/login"
            style={{
              display: 'inline-block',
              padding: '12px 28px',
              background: 'var(--accent)',
              color: '#fff',
              borderRadius: '8px',
              textDecoration: 'none',
              fontWeight: 600,
              fontSize: '16px',
            }}
          >
            Sign In
          </Link>
        </div>
      )}

      <div style={{ borderTop: '1px solid var(--border)', paddingTop: '24px', marginTop: '24px' }}>
        <h2 style={{ fontSize: '18px', marginBottom: '12px' }}>Portals</h2>
        <div style={{ display: 'flex', gap: '16px', justifyContent: 'center', flexWrap: 'wrap' }}>
          <Link to="/patient" style={{ color: 'var(--accent)', textDecoration: 'none' }}>
            Patient Portal
          </Link>
          <span style={{ color: 'var(--border)' }}>•</span>
          <Link to="/doctor" style={{ color: 'var(--accent)', textDecoration: 'none' }}>
            Doctor Portal
          </Link>
          <span style={{ color: 'var(--border)' }}>•</span>
          <Link to="/admin" style={{ color: 'var(--accent)', textDecoration: 'none' }}>
            Admin Portal
          </Link>
        </div>
      </div>
    </div>
  );
}
