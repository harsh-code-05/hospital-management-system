import { useEffect, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import api from '../api/axios';
import { useAuth } from '../context/useAuth';
import AvailabilityManager from '../components/AvailabilityManager';
import './DoctorDashboard.css';

export default function DoctorDashboard() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const userEmail = user?.email;
  const [doctorProfile, setDoctorProfile] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const fetchDoctorProfile = useCallback(async () => {
    if (!userEmail) return;
    setLoading(true);
    setError('');

    try {
      const res = await api.get('/doctors');
      const doctors = Array.isArray(res.data) ? res.data : [];
      const match = doctors.find(
        (doc) => doc.user?.email?.toLowerCase() === userEmail.toLowerCase(),
      );

      if (match) {
        setDoctorProfile(match);
      } else {
        setDoctorProfile(null);
        setError(
          `No active doctor profile is linked to account "${userEmail}". If you recently registered, your doctor application may still be pending administrative approval.`,
        );
      }
    } catch (err) {
      setError(
        err.response?.data?.message || 'Failed to load doctor profile. Please refresh.',
      );
    } finally {
      setLoading(false);
    }
  }, [userEmail]);

  useEffect(() => {
    let ignore = false;
    async function load() {
      if (!ignore) {
        await fetchDoctorProfile();
      }
    }
    load();
    return () => {
      ignore = true;
    };
  }, [fetchDoctorProfile]);

  const handleLogout = async () => {
    try {
      await logout();
      navigate('/login');
    } catch (err) {
      console.error('Logout error:', err);
    }
  };

  const doctorDisplayName = doctorProfile
    ? `Dr. ${doctorProfile.firstName} ${doctorProfile.lastName} (${userEmail})`
    : userEmail;

  return (
    <div className="doctor-dashboard">
      <header className="dashboard-header">
        <div className="dashboard-brand">
          <h1>Hospital Management System</h1>
          <span className="brand-badge">Doctor Portal</span>
        </div>
        <div className="dashboard-user-area">
          <span className="user-email-tag">{doctorDisplayName}</span>
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
        {loading ? (
          <div className="dashboard-alert alert-warning" role="status">
            Loading doctor profile...
          </div>
        ) : error ? (
          <div className="dashboard-alert alert-error" role="alert">
            <div>
              <strong>Doctor Profile Status:</strong> {error}
            </div>
          </div>
        ) : doctorProfile ? (
          <AvailabilityManager
            doctorId={doctorProfile.id}
            doctor={doctorProfile}
            isAdmin={false}
          />
        ) : null}
      </main>
    </div>
  );
}
