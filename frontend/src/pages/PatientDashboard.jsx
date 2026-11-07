import { useEffect, useState, useCallback, useRef } from 'react';
import { useNavigate, useSearchParams, useLocation } from 'react-router-dom';
import api from '../api/axios';
import { isDate, mutationErrorMessage, requireAppointmentResult, requirePatientProfileResult } from '../api/responseValidation';
import { useAuth } from '../context/useAuth';
import usePatientSlots from '../hooks/usePatientSlots';
import PatientAIAssistant from '../components/PatientAIAssistant';
import './PatientDashboard.css';

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

function getTodayIso() {
  const now = new Date();
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

export default function PatientDashboard() {
  const { user, logout, formDrafts } = useAuth();
  const navigate = useNavigate();
  const [searchParams] = typeof useSearchParams === 'function'
    ? useSearchParams()
    : [typeof URLSearchParams !== 'undefined' ? new URLSearchParams() : { get: () => null }];
  const location = typeof useLocation === 'function'
    ? useLocation()
    : { search: '', state: null };

  const urlDoctorId = searchParams?.get('doctorId') || location?.state?.selectedDoctorId;
  const urlSpecialtyId = searchParams?.get('specialtyId') || location?.state?.selectedSpecialtyId;

  const [bookingDraft] = useState(() => {
    const existing = formDrafts.get(user.id, 'booking');
    if (urlDoctorId) {
      return {
        ...existing,
        selectedDoctorId: String(urlDoctorId),
        selectedSpecialtyId: urlSpecialtyId ? String(urlSpecialtyId) : (existing?.selectedSpecialtyId ?? 'all'),
      };
    }
    return existing;
  });
  const [profileDraft] = useState(() => formDrafts.get(user.id, 'patientProfile'));
  const bookingInProgress = useRef(false);

  // Active view tab
  const [activeTab, setActiveTab] = useState(
    urlDoctorId ? 'book' : bookingDraft ? 'book' : profileDraft ? 'profile' : 'appointments'
  );

  // Patient profile state
  const [patientProfile, setPatientProfile] = useState(null);
  const [profileNotFound, setProfileNotFound] = useState(false);
  const [profileLoading, setProfileLoading] = useState(true);

  // Profile completion form state
  const [profileForm, setProfileForm] = useState(profileDraft ?? {
    firstName: '',
    lastName: '',
    dateOfBirth: '',
    gender: '',
    phone: '',
  });
  const [profileFormErrors, setProfileFormErrors] = useState({});
  const [isSubmittingProfile, setIsSubmittingProfile] = useState(false);
  const [profileSubmitError, setProfileSubmitError] = useState('');

  // Appointments state
  const [appointments, setAppointments] = useState([]);
  const [appointmentsLoading, setAppointmentsLoading] = useState(true);
  const [appointmentsError, setAppointmentsError] = useState('');
  const [statusFilter, setStatusFilter] = useState('ALL');

  // Global notice / alerts
  const [globalNotice, setGlobalNotice] = useState('');

  // Cancellation modal state
  const [cancellingAppt, setCancellingAppt] = useState(null);
  const [isCancelling, setIsCancelling] = useState(false);
  const [cancelError, setCancelError] = useState('');

  // Booking wizard state
  const [specializations, setSpecializations] = useState([]);
  const [doctors, setDoctors] = useState([]);
  const [selectedSpecialtyId, setSelectedSpecialtyId] = useState(
    bookingDraft?.selectedSpecialtyId ?? (urlSpecialtyId ? String(urlSpecialtyId) : 'all')
  );
  const { selection, selectDoctor, selectDate, refreshSlots, selectSlot, getCurrentSlot } =
    usePatientSlots(bookingDraft, getTodayIso());
  const {
    doctorId: selectedDoctorId, date: selectedDate, selectedSlotId, slots: availableSlots, status: slotsStatus,
  } = selection;
  const [bookingReason, setBookingReason] = useState(bookingDraft?.bookingReason ?? '');
  const [isBooking, setIsBooking] = useState(false);
  const [bookingError, setBookingError] = useState('');
  const [bookingSuccess, setBookingSuccess] = useState('');

  useEffect(() => {
    if (urlDoctorId) {
      setActiveTab('book');
      if (urlSpecialtyId) {
        setSelectedSpecialtyId(String(urlSpecialtyId));
      }
      if (String(selectedDoctorId) !== String(urlDoctorId)) {
        selectDoctor(String(urlDoctorId));
      }
    }
  }, [urlDoctorId, urlSpecialtyId, selectedDoctorId, selectDoctor]);

  // 1. Fetch Patient Profile
  const fetchProfile = useCallback(async () => {
    setProfileLoading(true);
    try {
      const res = await api.get('/patients/me');
      setPatientProfile(res.data);
      setProfileNotFound(false);
    } catch (err) {
      if (err.response?.status === 404) {
        setProfileNotFound(true);
        setPatientProfile(null);
      } else {
        console.error('Failed to load patient profile:', err);
      }
    } finally {
      setProfileLoading(false);
    }
  }, []);

  // 2. Fetch Appointments
  const fetchAppointments = useCallback(async () => {
    setAppointmentsLoading(true);
    setAppointmentsError('');
    try {
      const res = await api.get('/appointments');
      setAppointments(Array.isArray(res.data) ? res.data : []);
    } catch (err) {
      setAppointmentsError(
        err.response?.data?.message || 'Unable to load appointments.',
      );
    } finally {
      setAppointmentsLoading(false);
    }
  }, []);

  // 3. Fetch Doctors & Specializations
  const fetchDoctorsAndSpecialties = useCallback(async () => {
    try {
      const [specRes, docRes] = await Promise.all([
        api.get('/specializations'),
        api.get('/doctors'),
      ]);
      setSpecializations(Array.isArray(specRes.data) ? specRes.data : []);
      setDoctors(Array.isArray(docRes.data) ? docRes.data : []);
    } catch (err) {
      console.error('Failed to load doctors or specializations:', err);
    }
  }, []);

  // Initial Data Load
  useEffect(() => {
    let ignore = false;

    async function initialize() {
      if (!ignore) {
        await Promise.all([
          fetchProfile(),
          fetchAppointments(),
          fetchDoctorsAndSpecialties(),
        ]);
      }
    }

    initialize();

    return () => {
      ignore = true;
    };
  }, [fetchProfile, fetchAppointments, fetchDoctorsAndSpecialties]);

  // Handle Logout
  const handleLogout = async () => {
    try {
      await logout();
      navigate('/login');
    } catch (err) {
      console.error('Logout error:', err);
    }
  };

  // Handle Cancel Appointment
  const handleConfirmCancel = async () => {
    if (!cancellingAppt) return;
    setIsCancelling(true);
    setCancelError('');
    try {
      const response = await api.patch(`/appointments/${cancellingAppt.id}/cancel`);
      requireAppointmentResult(response, 'CANCELLED', (data) => data.id === cancellingAppt.id);
      setGlobalNotice(
        `Appointment #${cancellingAppt.id} was successfully cancelled.`,
      );
      setCancellingAppt(null);
      await fetchAppointments();
      refreshSlots();
    } catch (err) {
      setCancelError(
        mutationErrorMessage(err, 'Failed to cancel appointment. Please try again.'),
      );
    } finally {
      setIsCancelling(false);
    }
  };

  // Profile Completion Validation
  const validateProfileForm = () => {
    const errors = {};
    if (!profileForm.firstName.trim()) {
      errors.firstName = 'First name is required.';
    } else if (profileForm.firstName.trim().length > 50) {
      errors.firstName = 'First name cannot exceed 50 characters.';
    }

    if (!profileForm.lastName.trim()) {
      errors.lastName = 'Last name is required.';
    } else if (profileForm.lastName.trim().length > 50) {
      errors.lastName = 'Last name cannot exceed 50 characters.';
    }

    if (!profileForm.dateOfBirth) {
      errors.dateOfBirth = 'Date of birth is required.';
    } else {
      const today = getTodayIso();
      if (profileForm.dateOfBirth >= today) {
        errors.dateOfBirth = 'Date of birth must be in the past.';
      }
    }

    if (!profileForm.gender) {
      errors.gender = 'Gender is required.';
    }

    if (!profileForm.phone.trim()) {
      errors.phone = 'Phone number is required.';
    } else if (!/^[0-9+()\-\s]{7,20}$/.test(profileForm.phone.trim())) {
      errors.phone = 'Phone number must be between 7 and 20 valid characters (digits, +, -, spaces).';
    }

    return errors;
  };

  // Handle Profile Completion Submit
  const handleProfileSubmit = async (e) => {
    e.preventDefault();
    setProfileSubmitError('');
    const errors = validateProfileForm();
    if (Object.keys(errors).length > 0) {
      setProfileFormErrors(errors);
      return;
    }
    setProfileFormErrors({});
    setIsSubmittingProfile(true);
    formDrafts.save(user.id, 'patientProfile', profileForm);

    try {
      const response = await api.post('/patients/me', {
        firstName: profileForm.firstName.trim(),
        lastName: profileForm.lastName.trim(),
        dateOfBirth: profileForm.dateOfBirth,
        gender: profileForm.gender,
        phone: profileForm.phone.trim(),
      });
      requirePatientProfileResult(response, user.id);
      formDrafts.discard(user.id, 'patientProfile', profileForm);

      setGlobalNotice('Patient profile completed successfully! You can now book appointments.');
      await fetchProfile();
      await fetchAppointments();
    } catch (err) {
      if (err.response?.status === 409) {
        setGlobalNotice('Patient profile already exists. Profile loaded.');
        await fetchProfile();
        await fetchAppointments();
      } else if (err.response?.data?.errors) {
        setProfileFormErrors(err.response.data.errors);
      } else {
        setProfileSubmitError(
          mutationErrorMessage(err, 'Failed to complete profile. Please try again.'),
        );
      }
    } finally {
      setIsSubmittingProfile(false);
    }
  };

  // Handle Book Appointment
  const handleBookSubmit = async (e) => {
    e.preventDefault();
    if (bookingInProgress.current) return;
    setBookingError('');
    setBookingSuccess('');

    if (profileNotFound || !patientProfile?.id) {
      setBookingError(
        'Your patient profile is not yet completed. Please complete your profile to book an appointment.',
      );
      return;
    }

    if (!selectedDoctorId) {
      setBookingError('Please select a doctor.');
      return;
    }

    if (!isDate(selectedDate) || selectedDate < getTodayIso()) {
      setBookingError('Please select a valid appointment date.');
      return;
    }

    const slot = getCurrentSlot(selectedDoctorId, selectedDate, selectedSlotId);
    if (!slot) {
      setBookingError('Please select an available time slot.');
      return;
    }

    if (!bookingReason.trim()) {
      setBookingError('Please enter a reason for the visit.');
      return;
    }

    bookingInProgress.current = true;
    setIsBooking(true);
    const draft = { selectedSpecialtyId, selectedDoctorId, selectedDate, selectedSlotId, bookingReason };
    try {
      formDrafts.save(user.id, 'booking', draft);
      const request = {
        patientId: patientProfile.id,
        doctorId: Number(selectedDoctorId),
        slotId: slot.id,
        appointmentDate: selectedDate,
        reason: bookingReason.trim(),
      };
      const response = await api.post('/appointments', request);
      const booked = requireAppointmentResult(response, 'CONFIRMED', (data) =>
        data.patient?.id === request.patientId && data.doctor?.id === request.doctorId
        && data.slot?.id === request.slotId && data.slot?.date === request.appointmentDate,
      );
      formDrafts.discard(user.id, 'booking', draft);

      setBookingSuccess(`Appointment #${booked.id} confirmed successfully!`);
      selectSlot(null);
      setBookingReason('');
      await fetchAppointments();
      refreshSlots();
    } catch (err) {
      setBookingError(
        mutationErrorMessage(err, 'Failed to book appointment. Please check availability and try again.'),
      );
    } finally {
      bookingInProgress.current = false;
      setIsBooking(false);
    }
  };

  // Summary Metrics
  const totalCount = appointments.length;
  const confirmedCount = appointments.filter(
    (a) => a.status === 'CONFIRMED',
  ).length;
  const completedCount = appointments.filter(
    (a) => a.status === 'COMPLETED',
  ).length;
  const cancelledCount = appointments.filter(
    (a) => a.status === 'CANCELLED',
  ).length;

  // Filtered Appointments
  const filteredAppointments = appointments.filter((appt) => {
    if (statusFilter === 'ALL') return true;
    return appt.status === statusFilter;
  });

  // Filtered Doctors by Specialization
  const filteredDoctors =
    selectedSpecialtyId === 'all'
      ? doctors
      : doctors.filter(
          (doc) => String(doc.specialization?.id) === String(selectedSpecialtyId),
        );

  return (
    <div className="patient-dashboard">
      {/* Top Header */}
      <header className="dashboard-header">
        <div className="dashboard-brand">
          <h1>Hospital Management System</h1>
          <span className="brand-badge">Patient Portal</span>
        </div>
        <div className="dashboard-user-area">
          <span className="user-email-tag">
            {patientProfile
              ? `${patientProfile.firstName} ${patientProfile.lastName} (${user?.email})`
              : user?.email}
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
      <nav className="dashboard-tabs">
        <button
          type="button"
          className={`tab-btn ${activeTab === 'appointments' ? 'active' : ''}`}
          onClick={() => {
            setActiveTab('appointments');
            setGlobalNotice('');
          }}
        >
          My Appointments ({totalCount})
        </button>
        <button
          type="button"
          className={`tab-btn ${activeTab === 'book' ? 'active' : ''}`}
          onClick={() => {
            setActiveTab('book');
            setGlobalNotice('');
          }}
        >
          Book Appointment
        </button>
        <button
          type="button"
          className={`tab-btn ${activeTab === 'profile' ? 'active' : ''}`}
          onClick={() => {
            setActiveTab('profile');
            setGlobalNotice('');
          }}
        >
          My Profile
        </button>
        <button
          type="button"
          className={`tab-btn ${activeTab === 'assistant' ? 'active' : ''}`}
          onClick={() => {
            setActiveTab('assistant');
            setGlobalNotice('');
          }}
        >
          AI Assistant
        </button>
      </nav>

      {/* Main Content Area */}
      <main className="dashboard-content">
        {activeTab === 'assistant' && <PatientAIAssistant />}
        {/* Profile Uninitialized Alert */}
        {profileNotFound && (
          <div
            className="dashboard-alert alert-warning"
            role="alert"
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              flexWrap: 'wrap',
              gap: '12px',
            }}
          >
            <div>
              <strong>Profile Incomplete:</strong> Your patient profile has not been completed yet. Please fill in your profile details to unlock appointment booking.
            </div>
            <button
              type="button"
              className="btn btn-secondary"
              onClick={() => {
                setActiveTab('profile');
                setGlobalNotice('');
              }}
              style={{ whiteSpace: 'nowrap' }}
            >
              Complete Profile Now
            </button>
          </div>
        )}

        {/* Global Action Notice */}
        {globalNotice && (
          <div className="dashboard-alert alert-success" role="status">
            <span>{globalNotice}</span>
          </div>
        )}

        {/* VIEW 1: MY APPOINTMENTS */}
        {activeTab === 'appointments' && (
          <>
            {/* Metrics Row */}
            <section className="metrics-row">
              <div className="metric-tile">
                <span className="metric-label">Total Appointments</span>
                <span className="metric-val">{totalCount}</span>
              </div>
              <div className="metric-tile">
                <span className="metric-label">Upcoming / Confirmed</span>
                <span className="metric-val" style={{ color: '#16a34a' }}>
                  {confirmedCount}
                </span>
              </div>
              <div className="metric-tile">
                <span className="metric-label">Completed</span>
                <span className="metric-val" style={{ color: '#2563eb' }}>
                  {completedCount}
                </span>
              </div>
              <div className="metric-tile">
                <span className="metric-label">Cancelled</span>
                <span className="metric-val" style={{ color: '#dc2626' }}>
                  {cancelledCount}
                </span>
              </div>
            </section>

            {/* Filter Toolbar */}
            <div className="filter-toolbar">
              <div className="filter-btn-group">
                {['ALL', 'CONFIRMED', 'COMPLETED', 'CANCELLED', 'NO_SHOW'].map(
                  (status) => (
                    <button
                      key={status}
                      type="button"
                      className={`filter-btn ${statusFilter === status ? 'active' : ''}`}
                      onClick={() => setStatusFilter(status)}
                    >
                      {status === 'ALL'
                        ? 'All'
                        : status.charAt(0) + status.slice(1).toLowerCase()}
                    </button>
                  ),
                )}
              </div>
              <button
                type="button"
                className="btn-secondary"
                onClick={fetchAppointments}
                disabled={appointmentsLoading}
              >
                {appointmentsLoading ? 'Refreshing...' : '↻ Refresh'}
              </button>
            </div>

            {/* Error Message */}
            {appointmentsError && (
              <div className="dashboard-alert alert-error">
                {appointmentsError}
              </div>
            )}

            {/* Appointments Stack */}
            {appointmentsLoading && appointments.length === 0 ? (
              <div className="empty-placeholder">
                <p>Loading your appointments...</p>
              </div>
            ) : filteredAppointments.length === 0 ? (
              <div className="empty-placeholder">
                <h3>No appointments found</h3>
                <p>
                  {statusFilter === 'ALL'
                    ? "You haven't booked any appointments yet."
                    : `No appointments with status "${statusFilter}".`}
                </p>
                <button
                  type="button"
                  className="btn-primary"
                  onClick={() => setActiveTab('book')}
                >
                  Book an Appointment
                </button>
              </div>
            ) : (
              <div className="appointments-stack">
                {filteredAppointments.map((appt) => {
                  const statusClass =
                    appt.status === 'CONFIRMED'
                      ? 'status-confirmed'
                      : appt.status === 'COMPLETED'
                        ? 'status-completed'
                        : appt.status === 'CANCELLED'
                          ? 'status-cancelled'
                          : 'status-noshow';

                  const canCancel = appt.status === 'CONFIRMED';

                  return (
                    <article key={appt.id} className="appointment-card">
                      <div className="card-main">
                        <div className="card-header-line">
                          <span className="card-date-time">
                            📅 {formatDate(appt.slot?.date)} •{' '}
                            {formatTime(appt.slot?.startTime)} –{' '}
                            {formatTime(appt.slot?.endTime)}
                          </span>
                          <span className={`status-pill ${statusClass}`}>
                            {appt.status}
                          </span>
                        </div>

                        <div className="card-doctor-info">
                          <span className="doctor-name">
                            Dr. {appt.doctor?.firstName} {appt.doctor?.lastName}
                          </span>
                          <span className="doctor-specialty">
                            {appt.doctor?.specialization?.name}
                            {appt.doctor?.qualification &&
                              ` (${appt.doctor.qualification})`}
                            {appt.doctor?.experience != null &&
                              ` • ${appt.doctor.experience} yrs exp`}
                          </span>
                        </div>

                        {appt.reason && (
                          <div className="card-reason">
                            <strong>Reason:</strong> {appt.reason}
                          </div>
                        )}

                        <div className="card-footer-meta">
                          Appointment #{appt.id} • Booked on{' '}
                          {formatDate(appt.createdAt?.split('T')[0])}
                        </div>
                      </div>

                      {canCancel && (
                        <div className="card-actions">
                          <button
                            type="button"
                            className="btn-cancel-appt"
                            onClick={() => {
                              setCancelError('');
                              setCancellingAppt(appt);
                            }}
                          >
                            Cancel Appointment
                          </button>
                        </div>
                      )}
                    </article>
                  );
                })}
              </div>
            )}
          </>
        )}

        {/* VIEW 2: BOOK APPOINTMENT */}
        {activeTab === 'book' && (
          <div className="booking-card">
            <h2>Book an Appointment</h2>
            <p className="booking-subtitle">
              Choose a specialist, pick an available slot, and confirm your visit.
            </p>

            {profileNotFound && (
              <div
                className="dashboard-alert alert-warning"
                style={{
                  marginBottom: '20px',
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                  flexWrap: 'wrap',
                  gap: '12px',
                }}
              >
                <span>
                  <strong>Appointment booking is locked:</strong> Please complete your patient profile before scheduling an appointment.
                </span>
                <button
                  type="button"
                  className="btn btn-secondary"
                  onClick={() => {
                    setActiveTab('profile');
                    setGlobalNotice('');
                  }}
                  style={{ whiteSpace: 'nowrap' }}
                >
                  Go to Profile Form
                </button>
              </div>
            )}

            {bookingSuccess && (
              <div
                className="dashboard-alert alert-success"
                style={{ marginBottom: '20px' }}
              >
                <span>{bookingSuccess}</span>
              </div>
            )}

            {bookingError && (
              <div
                className="dashboard-alert alert-error"
                style={{ marginBottom: '20px' }}
              >
                <span>{bookingError}</span>
              </div>
            )}

            <form onSubmit={handleBookSubmit} className="form-layout" noValidate>
              {/* Step 1: Filter Specialization */}
              <div className="form-field">
                <label htmlFor="specialty-filter">
                  1. Filter by Specialization (Optional)
                </label>
                <select
                  id="specialty-filter"
                  className="form-control"
                  value={selectedSpecialtyId}
                  onChange={(e) => {
                    setSelectedSpecialtyId(e.target.value);
                    selectDoctor('');
                  }}
                  disabled={isBooking}
                >
                  <option value="all">All Specializations</option>
                  {specializations.map((spec) => (
                    <option key={spec.id} value={spec.id}>
                      {spec.name}
                    </option>
                  ))}
                </select>
              </div>

              {/* Step 2: Select Doctor */}
              <div className="form-field">
                <label htmlFor="doctor-select">2. Select Doctor *</label>
                <select
                  id="doctor-select"
                  className="form-control"
                  value={selectedDoctorId}
                  onChange={(e) => selectDoctor(e.target.value)}
                  disabled={isBooking}
                  required
                >
                  <option value="">-- Choose a doctor --</option>
                  {filteredDoctors.map((doc) => (
                    <option key={doc.id} value={doc.id}>
                      Dr. {doc.firstName} {doc.lastName} (
                      {doc.specialization?.name || 'General'}
                      {doc.qualification ? ` - ${doc.qualification}` : ''})
                    </option>
                  ))}
                </select>
                {filteredDoctors.length === 0 && (
                  <span
                    style={{
                      fontSize: '13px',
                      color: 'var(--text)',
                      marginTop: '4px',
                    }}
                  >
                    No doctors available for this specialization.
                  </span>
                )}
              </div>

              {/* Step 3: Date Picker */}
              <div className="form-field">
                <label htmlFor="appointment-date">3. Choose Date *</label>
                <input
                  id="appointment-date"
                  type="date"
                  className="form-control"
                  value={selectedDate}
                  min={getTodayIso()}
                  onChange={(e) => selectDate(e.target.value)}
                  disabled={isBooking}
                  required
                />
              </div>

              {/* Step 4: Available Slots Selection */}
              <div className="form-field">
                <label>4. Select Time Slot *</label>
                {!selectedDoctorId ? (
                  <span style={{ fontSize: '13px', color: 'var(--text)' }}>
                    Please select a doctor to view available slots.
                  </span>
                ) : slotsStatus === 'idle' ? (
                  <span style={{ fontSize: '13px', color: 'var(--text)' }}>
                    Please choose a valid date to view available slots.
                  </span>
                ) : slotsStatus === 'loading' ? (
                  <span style={{ fontSize: '13px', color: 'var(--text)' }}>
                    Loading available slots...
                  </span>
                ) : slotsStatus === 'error' ? (
                  <div role="alert" className="dashboard-alert alert-error">
                    Unable to load available slots. Please try again.
                  </div>
                ) : availableSlots.length === 0 ? (
                  <span style={{ fontSize: '13px', color: 'var(--text)' }}>
                    No available slots for this doctor on{' '}
                    {formatDate(selectedDate)}. Please choose another date or
                    doctor.
                  </span>
                ) : (
                  <div className="slots-grid">
                    {availableSlots.map((slot) => {
                      const isSelected = selectedSlotId === slot.id;
                      return (
                        <button
                          key={slot.id}
                          type="button"
                          className={`slot-pill ${isSelected ? 'active' : ''}`}
                          onClick={() => selectSlot(slot.id)}
                          disabled={isBooking}
                        >
                          {formatTime(slot.startTime)} – {formatTime(slot.endTime)}
                        </button>
                      );
                    })}
                  </div>
                )}
              </div>

              {/* Step 5: Reason for Visit */}
              <div className="form-field">
                <label htmlFor="appointment-reason">
                  5. Reason for Visit *
                </label>
                <textarea
                  id="appointment-reason"
                  className="form-control"
                  rows="3"
                  placeholder="Describe your symptoms or the purpose of your appointment"
                  value={bookingReason}
                  onChange={(e) => setBookingReason(e.target.value)}
                  disabled={isBooking}
                  required
                />
              </div>

              {/* Submit Button */}
              <button
                type="submit"
                className="btn-primary"
                disabled={
                  isBooking ||
                  slotsStatus !== 'ready' ||
                  !selectedDoctorId ||
                  !selectedSlotId ||
                  !bookingReason.trim() ||
                  profileNotFound
                }
              >
                {isBooking ? 'Confirming Booking...' : 'Confirm Appointment'}
              </button>
            </form>
          </div>
        )}

        {/* VIEW 3: MY PROFILE */}
        {activeTab === 'profile' && (
          <div className="profile-card">
            <h2>Patient Profile</h2>

            {profileLoading ? (
              <p>Loading profile information...</p>
            ) : profileNotFound ? (
              <div className="profile-completion-section">
                <div
                  className="dashboard-alert alert-warning"
                  style={{ marginBottom: '20px' }}
                >
                  <span>
                    <strong>Complete Your Profile:</strong> Please enter your details below to initialize your medical record and enable appointment booking.
                  </span>
                </div>

                {profileSubmitError && (
                  <div
                    className="dashboard-alert alert-error"
                    style={{ marginBottom: '20px' }}
                    role="alert"
                  >
                    <span>{profileSubmitError}</span>
                  </div>
                )}

                <form onSubmit={handleProfileSubmit} className="form-layout" noValidate>
                  <div className="form-field-row">
                    <div className="form-field">
                      <label htmlFor="profile-firstName">First Name *</label>
                      <input
                        id="profile-firstName"
                        type="text"
                        className="form-control"
                        placeholder="e.g. Jane"
                        value={profileForm.firstName}
                        onChange={(e) =>
                          setProfileForm((prev) => ({
                            ...prev,
                            firstName: e.target.value,
                          }))
                        }
                        disabled={isSubmittingProfile}
                        maxLength={50}
                        required
                      />
                      {profileFormErrors.firstName && (
                        <span className="form-field-error">
                          {profileFormErrors.firstName}
                        </span>
                      )}
                    </div>

                    <div className="form-field">
                      <label htmlFor="profile-lastName">Last Name *</label>
                      <input
                        id="profile-lastName"
                        type="text"
                        className="form-control"
                        placeholder="e.g. Doe"
                        value={profileForm.lastName}
                        onChange={(e) =>
                          setProfileForm((prev) => ({
                            ...prev,
                            lastName: e.target.value,
                          }))
                        }
                        disabled={isSubmittingProfile}
                        maxLength={50}
                        required
                      />
                      {profileFormErrors.lastName && (
                        <span className="form-field-error">
                          {profileFormErrors.lastName}
                        </span>
                      )}
                    </div>
                  </div>

                  <div className="form-field-row">
                    <div className="form-field">
                      <label htmlFor="profile-dob">Date of Birth *</label>
                      <input
                        id="profile-dob"
                        type="date"
                        className="form-control"
                        max={getTodayIso()}
                        value={profileForm.dateOfBirth}
                        onChange={(e) =>
                          setProfileForm((prev) => ({
                            ...prev,
                            dateOfBirth: e.target.value,
                          }))
                        }
                        disabled={isSubmittingProfile}
                        required
                      />
                      {profileFormErrors.dateOfBirth && (
                        <span className="form-field-error">
                          {profileFormErrors.dateOfBirth}
                        </span>
                      )}
                    </div>

                    <div className="form-field">
                      <label htmlFor="profile-gender">Gender *</label>
                      <select
                        id="profile-gender"
                        className="form-control"
                        value={profileForm.gender}
                        onChange={(e) =>
                          setProfileForm((prev) => ({
                            ...prev,
                            gender: e.target.value,
                          }))
                        }
                        disabled={isSubmittingProfile}
                        required
                      >
                        <option value="">Select Gender</option>
                        <option value="MALE">Male</option>
                        <option value="FEMALE">Female</option>
                        <option value="OTHER">Other</option>
                      </select>
                      {profileFormErrors.gender && (
                        <span className="form-field-error">
                          {profileFormErrors.gender}
                        </span>
                      )}
                    </div>
                  </div>

                  <div className="form-field">
                    <label htmlFor="profile-phone">Phone Number *</label>
                    <input
                      id="profile-phone"
                      type="tel"
                      className="form-control"
                      placeholder="e.g. +1 555-123-4567"
                      value={profileForm.phone}
                      onChange={(e) =>
                        setProfileForm((prev) => ({
                          ...prev,
                          phone: e.target.value,
                        }))
                      }
                      disabled={isSubmittingProfile}
                      maxLength={20}
                      required
                    />
                    {profileFormErrors.phone && (
                      <span className="form-field-error">
                        {profileFormErrors.phone}
                      </span>
                    )}
                  </div>

                  <div className="profile-item" style={{ marginTop: '8px', marginBottom: '8px' }}>
                    <span className="profile-item-label">Linked Account Email</span>
                    <span className="profile-item-value">{user?.email}</span>
                  </div>

                  <button
                    type="submit"
                    className="btn-primary"
                    disabled={isSubmittingProfile}
                    style={{ minWidth: '180px', marginTop: '12px' }}
                  >
                    {isSubmittingProfile ? 'Saving Profile...' : 'Complete Profile'}
                  </button>
                </form>
              </div>
            ) : (
              <div className="profile-grid">
                <div className="profile-item">
                  <span className="profile-item-label">Full Name</span>
                  <span className="profile-item-value">
                    {patientProfile?.firstName} {patientProfile?.lastName}
                  </span>
                </div>
                <div className="profile-item">
                  <span className="profile-item-label">Patient ID</span>
                  <span className="profile-item-value">
                    #{patientProfile?.id}
                  </span>
                </div>
                <div className="profile-item">
                  <span className="profile-item-label">Email Address</span>
                  <span className="profile-item-value">
                    {patientProfile?.user?.email || user?.email}
                  </span>
                </div>
                <div className="profile-item">
                  <span className="profile-item-label">Phone Number</span>
                  <span className="profile-item-value">
                    {patientProfile?.phone || 'Not provided'}
                  </span>
                </div>
                <div className="profile-item">
                  <span className="profile-item-label">Date of Birth</span>
                  <span className="profile-item-value">
                    {formatDate(patientProfile?.dateOfBirth)}
                  </span>
                </div>
                <div className="profile-item">
                  <span className="profile-item-label">Gender</span>
                  <span className="profile-item-value">
                    {patientProfile?.gender || 'Not specified'}
                  </span>
                </div>
                <div className="profile-item">
                  <span className="profile-item-label">System User ID</span>
                  <span className="profile-item-value">
                    #{patientProfile?.user?.id}
                  </span>
                </div>
                <div className="profile-item">
                  <span className="profile-item-label">System Role</span>
                  <span className="profile-item-value">
                    {patientProfile?.user?.role || user?.role}
                  </span>
                </div>
              </div>
            )}
          </div>
        )}
      </main>

      {/* Confirmation Modal for Appointment Cancellation */}
      {cancellingAppt && (
        <div
          className="modal-backdrop"
          onClick={() => !isCancelling && setCancellingAppt(null)}
        >
          <div
            className="modal-dialog"
            onClick={(e) => e.stopPropagation()}
            role="dialog"
            aria-modal="true"
            aria-labelledby="cancel-modal-title"
          >
            <h3 id="cancel-modal-title">Cancel Appointment</h3>
            <p>
              Are you sure you want to cancel your appointment with{' '}
              <strong>
                Dr. {cancellingAppt.doctor?.firstName}{' '}
                {cancellingAppt.doctor?.lastName}
              </strong>{' '}
              on <strong>{formatDate(cancellingAppt.slot?.date)}</strong> at{' '}
              <strong>{formatTime(cancellingAppt.slot?.startTime)}</strong>?
            </p>

            {cancelError && (
              <div
                className="dashboard-alert alert-error"
                style={{ marginBottom: '16px' }}
              >
                <span>{cancelError}</span>
              </div>
            )}

            <div className="modal-actions">
              <button
                type="button"
                className="btn-secondary"
                onClick={() => setCancellingAppt(null)}
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
                {isCancelling ? 'Cancelling...' : 'Yes, Cancel Appointment'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
