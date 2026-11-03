import { useEffect, useState, useCallback, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import api from '../api/axios';
import { useAuth } from '../context/useAuth';
import AvailabilityManager from '../components/AvailabilityManager';
import AppointmentListState from '../components/AppointmentListState';
import AppointmentMetricsState from '../components/AppointmentMetricsState';
import { RequestSequencer, executeSequencedRequest } from '../utils/requestSequencer';
import './DoctorDashboard.css';

function formatDate(dateStr) {
  if (!dateStr) return 'N/A';
  try {
    const [year, month, day] = dateStr.split('-');
    if (!year || !month || !day) return dateStr;
    const date = new Date(Number(year), Number(month) - 1, Number(day));
    return date.toLocaleDateString(undefined, {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
    });
  } catch {
    return dateStr;
  }
}

function formatTime(timeStr) {
  if (!timeStr) return '';
  const parts = timeStr.split(':');
  if (parts.length < 2) return timeStr;
  const hours = parseInt(parts[0], 10);
  const minutes = parts[1];
  const ampm = hours >= 12 ? 'PM' : 'AM';
  const displayHours = hours % 12 || 12;
  return `${displayHours}:${minutes} ${ampm}`;
}

export default function DoctorDashboard() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const userEmail = user?.email;
  const [doctorProfile, setDoctorProfile] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  // Active section tab: 'appointments' or 'availability'
  const [activeTab, setActiveTab] = useState('appointments');

  // Appointments state
  const [appointments, setAppointments] = useState([]);
  const [appointmentsLoading, setAppointmentsLoading] = useState(true);
  const [appointmentsError, setAppointmentsError] = useState('');
  const [appointmentsLoaded, setAppointmentsLoaded] = useState(false);
  const [statusFilter, setStatusFilter] = useState('ALL');

  // Global notice/banner
  const [globalNotice, setGlobalNotice] = useState('');

  // Complete appointment modal state
  const [completingAppt, setCompletingAppt] = useState(null);
  const [isCompleting, setIsCompleting] = useState(false);
  const [completeError, setCompleteError] = useState('');

  // Cancel appointment modal state
  const [cancellingAppt, setCancellingAppt] = useState(null);
  const [isCancelling, setIsCancelling] = useState(false);
  const [cancelError, setCancelError] = useState('');

  const sequencerRef = useRef(null);
  if (sequencerRef.current == null) {
    sequencerRef.current = new RequestSequencer();
  }

  useEffect(() => {
    sequencerRef.current.mount();
    return () => {
      sequencerRef.current.dispose();
    };
  }, []);

  // 1. Fetch Doctor Profile
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

  // 2. Fetch Appointments for logged-in Doctor
  const fetchAppointments = useCallback(async () => {
    return executeSequencedRequest(
      sequencerRef.current,
      async (signal) => {
        const res = await api.get('/appointments', { signal });
        if (!Array.isArray(res.data)) {
          throw new Error('Invalid appointment response');
        }
        return res.data;
      },
      {
        onStart: () => {
          setAppointmentsLoading(true);
          setAppointmentsError('');
        },
        onSuccess: (data) => {
          setAppointments(data);
          setAppointmentsLoaded(true);
          setAppointmentsLoading(false);
        },
        onError: (err) => {
          setAppointmentsError(
            err.response?.data?.message || 'Failed to load appointments. Please refresh.',
          );
          setAppointmentsLoading(false);
        },
      },
    );
  }, []);

  // Initial Doctor Profile Load
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

  // Fetch appointments when doctor profile is loaded
  useEffect(() => {
    let ignore = false;
    if (doctorProfile) {
      async function loadAppts() {
        if (!ignore) {
          await fetchAppointments();
        }
      }
      loadAppts();
    }
    return () => {
      ignore = true;
    };
  }, [doctorProfile, fetchAppointments]);

  // Logout Handler
  const handleLogout = async () => {
    try {
      await logout();
      navigate('/login');
    } catch (err) {
      console.error('Logout error:', err);
    }
  };

  // Complete Appointment Execution
  const handleConfirmComplete = async () => {
    if (!completingAppt) return;
    setIsCompleting(true);
    setCompleteError('');
    try {
      await api.patch(`/appointments/${completingAppt.id}/complete`);
      const notice = `Appointment #${completingAppt.id} was marked as completed.`;
      setCompletingAppt(null);
      const refreshed = await fetchAppointments();
      setGlobalNotice(refreshed
        ? notice
        : `${notice} The list could not be refreshed; displayed data may be stale.`);
    } catch (err) {
      setCompleteError(
        err.response?.data?.message ||
          'Failed to complete appointment. Please check appointment status and time.',
      );
    } finally {
      setIsCompleting(false);
    }
  };

  // Cancel Appointment Execution
  const handleConfirmCancel = async () => {
    if (!cancellingAppt) return;
    setIsCancelling(true);
    setCancelError('');
    try {
      await api.patch(`/appointments/${cancellingAppt.id}/cancel`);
      const notice = `Appointment #${cancellingAppt.id} was successfully cancelled. The slot has been released.`;
      setCancellingAppt(null);
      const refreshed = await fetchAppointments();
      setGlobalNotice(refreshed
        ? notice
        : `${notice} The list could not be refreshed; displayed data may be stale.`);
    } catch (err) {
      setCancelError(
        err.response?.data?.message ||
          'Failed to cancel appointment. Please check appointment status.',
      );
    } finally {
      setIsCancelling(false);
    }
  };

  // Summary Metrics
  const totalCount = appointments.length;
  const confirmedCount = appointments.filter((a) => a.status === 'CONFIRMED').length;
  const completedCount = appointments.filter((a) => a.status === 'COMPLETED').length;
  const cancelledCount = appointments.filter((a) => a.status === 'CANCELLED').length;
  const noShowCount = appointments.filter((a) => a.status === 'NO_SHOW').length;

  // Filtered Appointments
  const filteredAppointments = appointments.filter((appt) => {
    if (statusFilter === 'ALL') return true;
    return appt.status === statusFilter;
  });

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

      {/* Tabs Navigation */}
      {doctorProfile && !loading && (
        <nav className="dashboard-tabs" aria-label="Doctor portal tabs">
          <button
            type="button"
            className={`tab-btn ${activeTab === 'appointments' ? 'active' : ''}`}
            onClick={() => setActiveTab('appointments')}
          >
            My Appointments {appointmentsLoaded ? `(${totalCount})` : ''}
          </button>
          <button
            type="button"
            className={`tab-btn ${activeTab === 'availability' ? 'active' : ''}`}
            onClick={() => setActiveTab('availability')}
          >
            Availability & Slots
          </button>
        </nav>
      )}

      <main className="dashboard-content">
        {/* Global Notice Alert */}
        {globalNotice && (
          <div className="dashboard-alert alert-success" role="status">
            <div style={{ flex: 1 }}>{globalNotice}</div>
            <button
              type="button"
              onClick={() => setGlobalNotice('')}
              style={{
                background: 'none',
                border: 'none',
                cursor: 'pointer',
                fontWeight: 'bold',
                color: 'inherit',
              }}
              aria-label="Dismiss notice"
            >
              ×
            </button>
          </div>
        )}

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
          <>
            {/* Tab 1: My Appointments */}
            {activeTab === 'appointments' && (
              <>
                {/* Summary Metrics */}
                <AppointmentMetricsState
                  loading={appointmentsLoading}
                  error={appointmentsError}
                  hasLoaded={appointmentsLoaded}
                  metrics={{
                    total: totalCount,
                    confirmed: confirmedCount,
                    completed: completedCount,
                    cancelled: cancelledCount,
                    noShow: noShowCount,
                  }}
                />

                {/* Status Filter Toolbar */}
                <div className="filter-toolbar">
                  <div className="filter-btn-group" role="group" aria-label="Status filter">
                    {['ALL', 'CONFIRMED', 'COMPLETED', 'CANCELLED', 'NO_SHOW'].map((st) => (
                      <button
                        key={st}
                        type="button"
                        className={`filter-btn ${statusFilter === st ? 'active' : ''}`}
                        onClick={() => setStatusFilter(st)}
                      >
                        {st === 'ALL' ? 'All Appointments' : st}
                      </button>
                    ))}
                  </div>

                  <button
                    type="button"
                    className="btn-secondary"
                    onClick={fetchAppointments}
                    disabled={appointmentsLoading}
                  >
                    {appointmentsLoading ? 'Refreshing...' : 'Refresh List'}
                  </button>
                </div>

                {/* Appointments List / Stack */}
                <AppointmentListState
                  loading={appointmentsLoading}
                  error={appointmentsError}
                  hasLoaded={appointmentsLoaded}
                  isEmpty={filteredAppointments.length === 0}
                  loadingMessage="Loading your appointments..."
                  emptyMessage={statusFilter === 'ALL'
                    ? 'You do not have any patient appointments booked yet. Patient bookings will appear here once slots are reserved.'
                    : `You have no ${statusFilter.toLowerCase()} appointments.`}
                >
                  <div className="appointments-stack">
                    {filteredAppointments.map((appt) => {
                      const patientName = appt.patient
                        ? `${appt.patient.firstName} ${appt.patient.lastName}`
                        : 'Patient Details Unavailable';
                      const patientDob = formatDate(appt.patient?.dateOfBirth);
                      const patientPhone = appt.patient?.phone || 'Not provided';
                      const slotDate = formatDate(appt.slot?.date);
                      const slotTime = `${formatTime(appt.slot?.startTime)} – ${formatTime(
                        appt.slot?.endTime,
                      )}`;
                      const statusClass =
                        appt.status === 'CONFIRMED'
                          ? 'status-confirmed'
                          : appt.status === 'COMPLETED'
                          ? 'status-completed'
                          : appt.status === 'CANCELLED'
                          ? 'status-cancelled'
                          : 'status-noshow';

                      return (
                        <div key={appt.id} className="appointment-card">
                          <div className="card-main">
                            <div className="card-header-line">
                              <span className="card-date-time">
                                {slotDate} • {slotTime}
                              </span>
                              <span className={`status-pill ${statusClass}`}>
                                {appt.status}
                              </span>
                            </div>

                            {/* Patient Information Grid */}
                            <div className="patient-info-grid">
                              <div className="patient-info-item">
                                <span className="patient-info-label">Patient Name</span>
                                <span className="patient-info-val">{patientName}</span>
                              </div>
                              <div className="patient-info-item">
                                <span className="patient-info-label">Date of Birth</span>
                                <span className="patient-info-val">{patientDob}</span>
                              </div>
                              <div className="patient-info-item">
                                <span className="patient-info-label">Phone</span>
                                <span className="patient-info-val">{patientPhone}</span>
                              </div>
                            </div>

                            {/* Appointment Reason */}
                            <div className="card-reason">
                              <strong>Reason for Visit:</strong>{' '}
                              {appt.reason || 'General Consultation'}
                            </div>

                            {/* Footer Meta */}
                            <div className="card-footer-meta">
                              Appointment #{appt.id} • Booked on{' '}
                              {formatDate(appt.createdAt ? appt.createdAt.split('T')[0] : '')}
                            </div>
                          </div>

                          {/* Action Buttons for Confirmed Appointments */}
                          {appt.status === 'CONFIRMED' && (
                            <div className="card-actions">
                              <button
                                type="button"
                                className="btn-complete-appt"
                                onClick={() => {
                                  setCompletingAppt(appt);
                                  setCompleteError('');
                                }}
                              >
                                Complete
                              </button>
                              <button
                                type="button"
                                className="btn-cancel-appt"
                                onClick={() => {
                                  setCancellingAppt(appt);
                                  setCancelError('');
                                }}
                              >
                                Cancel
                              </button>
                            </div>
                          )}
                        </div>
                      );
                    })}
                  </div>
                </AppointmentListState>
              </>
            )}

            {/* Tab 2: Availability & Slots */}
            {activeTab === 'availability' && (
              <AvailabilityManager
                doctorId={doctorProfile.id}
                doctor={doctorProfile}
                isAdmin={false}
                onSlotsGenerated={fetchAppointments}
              />
            )}
          </>
        ) : null}
      </main>

      {/* Complete Appointment Confirmation Modal */}
      {completingAppt && (
        <div
          className="modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-labelledby="complete-modal-title"
        >
          <div className="modal-dialog">
            <h3 id="complete-modal-title">Complete Appointment</h3>
            <p>
              Are you sure you want to mark Appointment #{completingAppt.id} with{' '}
              <strong>
                {completingAppt.patient
                  ? `${completingAppt.patient.firstName} ${completingAppt.patient.lastName}`
                  : 'Patient'}
              </strong>{' '}
              as completed?
            </p>
            <div style={{ fontSize: '13px', color: 'var(--text)', marginBottom: '16px' }}>
              <strong>Scheduled Time:</strong> {formatDate(completingAppt.slot?.date)} (
              {formatTime(completingAppt.slot?.startTime)} –{' '}
              {formatTime(completingAppt.slot?.endTime)})
            </div>

            {completeError && (
              <div
                className="dashboard-alert alert-error"
                style={{ marginBottom: '16px' }}
                role="alert"
              >
                {completeError}
              </div>
            )}

            <div className="modal-actions">
              <button
                type="button"
                className="btn-secondary"
                onClick={() => {
                  setCompletingAppt(null);
                  setCompleteError('');
                }}
                disabled={isCompleting}
              >
                Cancel
              </button>
              <button
                type="button"
                className="btn-confirm-complete"
                onClick={handleConfirmComplete}
                disabled={isCompleting}
              >
                {isCompleting ? 'Completing...' : 'Mark as Completed'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Cancel Appointment Confirmation Modal */}
      {cancellingAppt && (
        <div
          className="modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-labelledby="cancel-modal-title"
        >
          <div className="modal-dialog">
            <h3 id="cancel-modal-title">Cancel Appointment</h3>
            <p>
              Are you sure you want to cancel Appointment #{cancellingAppt.id} with{' '}
              <strong>
                {cancellingAppt.patient
                  ? `${cancellingAppt.patient.firstName} ${cancellingAppt.patient.lastName}`
                  : 'Patient'}
              </strong>
              ? The appointment slot will be released back to available status.
            </p>
            <div style={{ fontSize: '13px', color: 'var(--text)', marginBottom: '16px' }}>
              <strong>Scheduled Time:</strong> {formatDate(cancellingAppt.slot?.date)} (
              {formatTime(cancellingAppt.slot?.startTime)} –{' '}
              {formatTime(cancellingAppt.slot?.endTime)})
            </div>

            {cancelError && (
              <div
                className="dashboard-alert alert-error"
                style={{ marginBottom: '16px' }}
                role="alert"
              >
                {cancelError}
              </div>
            )}

            <div className="modal-actions">
              <button
                type="button"
                className="btn-secondary"
                onClick={() => {
                  setCancellingAppt(null);
                  setCancelError('');
                }}
                disabled={isCancelling}
              >
                Keep Appointment
              </button>
              <button
                type="button"
                className="btn-danger"
                onClick={handleConfirmCancel}
                disabled={isCancelling}
              >
                {isCancelling ? 'Cancelling...' : 'Confirm Cancellation'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
