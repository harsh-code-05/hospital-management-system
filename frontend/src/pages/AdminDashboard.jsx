import { useEffect, useState, useCallback, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import api from '../api/axios';
import { mutationErrorMessage, requireAppointmentResult } from '../api/responseValidation';
import { useAuth } from '../context/useAuth';
import AvailabilityManager from '../components/AvailabilityManager';
import AppointmentListState from '../components/AppointmentListState';
import AppointmentMetricsState from '../components/AppointmentMetricsState';
import { RequestSequencer, executeSequencedRequest } from '../utils/requestSequencer';
import './AdminDashboard.css';

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

export default function AdminDashboard() {
  const { user, logout, formDrafts } = useAuth();
  const navigate = useNavigate();

  // Active section tab: 'appointments' or 'availability'
  const [activeTab, setActiveTab] = useState(() =>
    formDrafts.get(user.id, 'availability') ? 'availability' : 'appointments',
  );

  // Appointments state
  const [appointments, setAppointments] = useState([]);
  const [appointmentsLoading, setAppointmentsLoading] = useState(true);
  const [appointmentsError, setAppointmentsError] = useState('');
  const [appointmentsLoaded, setAppointmentsLoaded] = useState(false);

  // Doctor list for filter dropdown
  const [doctors, setDoctors] = useState([]);

  // Client-side filters
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [doctorFilter, setDoctorFilter] = useState('ALL');
  const [dateFilter, setDateFilter] = useState('');

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

  // Fetch all hospital appointments (admin gets all)
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

  // Fetch doctors for filter dropdown
  const fetchDoctors = useCallback(async () => {
    try {
      const res = await api.get('/doctors');
      setDoctors(Array.isArray(res.data) ? res.data : []);
    } catch {
      // Non-critical — filter just won't have doctor options
      setDoctors([]);
    }
  }, []);

  // Initial data load
  useEffect(() => {
    let ignore = false;
    async function load() {
      if (!ignore) {
        await Promise.all([fetchAppointments(), fetchDoctors()]);
      }
    }
    load();
    return () => {
      ignore = true;
    };
  }, [fetchAppointments, fetchDoctors]);

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
      const response = await api.patch(`/appointments/${completingAppt.id}/complete`);
      requireAppointmentResult(response, 'COMPLETED', (data) => data.id === completingAppt.id);
      const notice = `Appointment #${completingAppt.id} was marked as completed.`;
      setCompletingAppt(null);
      const refreshed = await fetchAppointments();
      setGlobalNotice(refreshed
        ? notice
        : `${notice} The list could not be refreshed; displayed data may be stale.`);
    } catch (err) {
      setCompleteError(
        mutationErrorMessage(err, 'Failed to complete appointment. Please check appointment status and time.'),
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
      const response = await api.patch(`/appointments/${cancellingAppt.id}/cancel`);
      requireAppointmentResult(response, 'CANCELLED', (data) => data.id === cancellingAppt.id);
      const notice = `Appointment #${cancellingAppt.id} was successfully cancelled. The slot has been released.`;
      setCancellingAppt(null);
      const refreshed = await fetchAppointments();
      setGlobalNotice(refreshed
        ? notice
        : `${notice} The list could not be refreshed; displayed data may be stale.`);
    } catch (err) {
      setCancelError(
        mutationErrorMessage(err, 'Failed to cancel appointment. Please check appointment status.'),
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

  // Filtered Appointments (status + doctor + date)
  const filteredAppointments = appointments.filter((appt) => {
    if (statusFilter !== 'ALL' && appt.status !== statusFilter) return false;
    if (doctorFilter !== 'ALL') {
      const apptDoctorId = appt.doctor?.id || appt.slot?.doctor?.id;
      if (String(apptDoctorId) !== doctorFilter) return false;
    }
    if (dateFilter && appt.slot?.date !== dateFilter) return false;
    return true;
  });

  // Build doctor options for the dropdown
  const doctorOptions = doctors.map((doc) => ({
    id: doc.id,
    label: `Dr. ${doc.firstName} ${doc.lastName}${doc.specialization?.name ? ` — ${doc.specialization.name}` : ''}`,
  }));

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

      {/* Tabs Navigation */}
      <nav className="dashboard-tabs" aria-label="Admin portal tabs">
        <button
          type="button"
          className={`tab-btn ${activeTab === 'appointments' ? 'active' : ''}`}
          onClick={() => setActiveTab('appointments')}
        >
          All Appointments {appointmentsLoaded ? `(${totalCount})` : ''}
        </button>
        <button
          type="button"
          className={`tab-btn ${activeTab === 'availability' ? 'active' : ''}`}
          onClick={() => setActiveTab('availability')}
        >
          Doctor Availability &amp; Slots
        </button>
      </nav>

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

        {/* Tab 1: All Appointments */}
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

            {/* Filter Toolbar */}
            <div className="filter-toolbar">
              <div className="filter-btn-group" role="group" aria-label="Status filter">
                {['ALL', 'CONFIRMED', 'COMPLETED', 'CANCELLED', 'NO_SHOW'].map((st) => (
                  <button
                    key={st}
                    type="button"
                    className={`filter-btn ${statusFilter === st ? 'active' : ''}`}
                    onClick={() => setStatusFilter(st)}
                  >
                    {st === 'ALL' ? 'All Statuses' : st}
                  </button>
                ))}
              </div>

              <div className="filter-controls">
                {/* Doctor Filter Dropdown */}
                <select
                  className="filter-select"
                  value={doctorFilter}
                  onChange={(e) => setDoctorFilter(e.target.value)}
                  aria-label="Filter by doctor"
                >
                  <option value="ALL">All Doctors</option>
                  {doctorOptions.map((doc) => (
                    <option key={doc.id} value={String(doc.id)}>
                      {doc.label}
                    </option>
                  ))}
                </select>

                {/* Date Filter Input */}
                <input
                  type="date"
                  className="filter-date-input"
                  value={dateFilter}
                  onChange={(e) => setDateFilter(e.target.value)}
                  aria-label="Filter by appointment date"
                />

                {dateFilter && (
                  <button
                    type="button"
                    className="btn-clear-date"
                    onClick={() => setDateFilter('')}
                    aria-label="Clear date filter"
                  >
                    Clear Date
                  </button>
                )}

                <button
                  type="button"
                  className="btn-secondary"
                  onClick={fetchAppointments}
                  disabled={appointmentsLoading}
                >
                  {appointmentsLoading ? 'Refreshing...' : 'Refresh List'}
                </button>
              </div>
            </div>

            {/* Appointments List / Stack */}
            <AppointmentListState
              loading={appointmentsLoading}
              error={appointmentsError}
              hasLoaded={appointmentsLoaded}
              isEmpty={filteredAppointments.length === 0}
              loadingMessage="Loading all hospital appointments..."
              emptyMessage={statusFilter === 'ALL' && doctorFilter === 'ALL' && !dateFilter
                ? 'There are no appointments in the hospital system yet. Patient bookings will appear here once appointments are booked.'
                : 'No appointments match the current filter criteria. Try adjusting the status, doctor, or date filters.'}
            >
              <div className="appointments-stack">
                {filteredAppointments.map((appt) => {
                  const patientName = appt.patient
                    ? `${appt.patient.firstName} ${appt.patient.lastName}`
                    : 'Patient Details Unavailable';
                  const patientDob = formatDate(appt.patient?.dateOfBirth);
                  const patientPhone = appt.patient?.phone || 'Not provided';

                  const doctor = appt.doctor || appt.slot?.doctor;
                  const doctorName = doctor
                    ? `Dr. ${doctor.firstName} ${doctor.lastName}`
                    : 'Doctor Details Unavailable';
                  const doctorSpec = doctor?.specialization?.name || 'General';

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
                          <span className="card-appt-id">#{appt.id}</span>
                          <span className="card-date-time">
                            {slotDate} • {slotTime}
                          </span>
                          <span className={`status-pill ${statusClass}`}>
                            {appt.status}
                          </span>
                        </div>

                        {/* Patient & Doctor Information Grid */}
                        <div className="info-grid">
                          <div className="info-grid-item">
                            <span className="info-grid-label">Patient Name</span>
                            <span className="info-grid-val">{patientName}</span>
                          </div>
                          <div className="info-grid-item">
                            <span className="info-grid-label">Date of Birth</span>
                            <span className="info-grid-val">{patientDob}</span>
                          </div>
                          <div className="info-grid-item">
                            <span className="info-grid-label">Phone</span>
                            <span className="info-grid-val">{patientPhone}</span>
                          </div>
                          <div className="info-grid-item">
                            <span className="info-grid-label">Assigned Doctor</span>
                            <span className="info-grid-val">{doctorName}</span>
                          </div>
                          <div className="info-grid-item">
                            <span className="info-grid-label">Specialization</span>
                            <span className="info-grid-val">{doctorSpec}</span>
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

        {/* Tab 2: Doctor Availability & Slots */}
        {activeTab === 'availability' && (
          <AvailabilityManager isAdmin={true} onSlotsGenerated={fetchAppointments} />
        )}
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
            <div style={{ fontSize: '13px', color: 'var(--text)', marginBottom: '8px' }}>
              <strong>Doctor:</strong>{' '}
              {completingAppt.doctor
                ? `Dr. ${completingAppt.doctor.firstName} ${completingAppt.doctor.lastName}`
                : completingAppt.slot?.doctor
                ? `Dr. ${completingAppt.slot.doctor.firstName} ${completingAppt.slot.doctor.lastName}`
                : 'N/A'}
            </div>
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
            <div style={{ fontSize: '13px', color: 'var(--text)', marginBottom: '8px' }}>
              <strong>Doctor:</strong>{' '}
              {cancellingAppt.doctor
                ? `Dr. ${cancellingAppt.doctor.firstName} ${cancellingAppt.doctor.lastName}`
                : cancellingAppt.slot?.doctor
                ? `Dr. ${cancellingAppt.slot.doctor.firstName} ${cancellingAppt.slot.doctor.lastName}`
                : 'N/A'}
            </div>
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
