import { useNavigate } from 'react-router-dom';
import { useAuth } from '../context/useAuth';
import AvailabilityManager from '../components/AvailabilityManager';
import './AdminDashboard.css';

export default function AdminDashboard() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const handleLogout = async () => {
    try {
      await logout();
      navigate('/login');
    } catch (err) {
      console.error('Logout error:', err);
    }
  };

  return (
    <div className="admin-dashboard">
      <header className="dashboard-header">
        <div className="dashboard-brand">
          <h1>Hospital Management System</h1>
          <span className="brand-badge">Admin Portal</span>
        </div>
        <div className="dashboard-user-area">
          <span className="user-email-tag">
            Administrator ({user?.email})
          </span>
          <button
            type="button"
            className="btn-secondary"
            onClick={handleLogout}
          >
            Sign Out
          </button>
        </div>
      </header>

      <main className="dashboard-content">
        <AvailabilityManager isAdmin={true} />
      </main>
    </div>
  );
}
