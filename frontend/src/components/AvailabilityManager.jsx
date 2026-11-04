import { useEffect, useState, useCallback, useRef, useMemo } from 'react';
import api from '../api/axios';
import { mutationErrorMessage, requireAvailabilityResult, requireSlotGenerationResult } from '../api/responseValidation';
import { useAuth } from '../context/useAuth';
import './AvailabilityManager.css';

/**
 * Format ISO date (YYYY-MM-DD) to friendly string (e.g., Dec 11, 2026)
 */
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

/**
 * Format 24-hr time (HH:mm or HH:mm:ss) to 12-hr AM/PM format
 */
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

/**
 * Returns today's date formatted as YYYY-MM-DD
 */
function getTodayIso() {
  const now = new Date();
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

/**
 * Convert time string (HH:mm or HH:mm:ss) to total minutes
 */
function parseTimeToMinutes(timeStr) {
  if (!timeStr) return 0;
  const parts = timeStr.split(':');
  const h = parseInt(parts[0], 10) || 0;
  const m = parseInt(parts[1], 10) || 0;
  return h * 60 + m;
}

/**
 * AvailabilityManager component
 *
 * Props:
 *   doctorId: (optional) number/string. In Doctor mode, locks management to this doctor.
 *   doctor: (optional) Doctor object or display name for the locked doctor.
 *   isAdmin: (optional, default false) boolean. If true (or if doctorId is omitted),
 *            enables doctor selection from GET /api/doctors.
 *   onSlotsGenerated: (optional) callback function invoked after slot generation.
 */
export default function AvailabilityManager({
  doctorId,
  doctor,
  isAdmin = false,
  onSlotsGenerated,
}) {
  const { user, formDrafts } = useAuth();
  const [formDraft] = useState(() => formDrafts.get(user.id, 'availability'));
  // Mode detection
  const isDoctorMode = Boolean(doctorId) && !isAdmin;

  // Doctors list (for Admin mode)
  const [doctorsList, setDoctorsList] = useState([]);
  const [doctorsLoading, setDoctorsLoading] = useState(false);
  const [selectedDoctorId, setSelectedDoctorId] = useState(
    formDraft?.doctorId ?? (doctorId ? String(doctorId) : ''),
  );

  // Active doctor derived state
  const activeDoctorId = isDoctorMode ? String(doctorId) : selectedDoctorId;

  // Availability creation form state
  const [formDate, setFormDate] = useState(formDraft?.date ?? getTodayIso());
  const [formStartTime, setFormStartTime] = useState(formDraft?.startTime ?? '09:00');
  const [formEndTime, setFormEndTime] = useState(formDraft?.endTime ?? '12:00');
  const [formSubmitting, setFormSubmitting] = useState(false);
  const [formError, setFormError] = useState('');
  const [formSuccess, setFormSuccess] = useState('');

  // Track availability records created in current session
  const [recentAvailabilities, setRecentAvailabilities] = useState([]);

  // Slot generation state & guards
  const [isGeneratingId, setIsGeneratingId] = useState(null);
  const [generationError, setGenerationError] = useState('');
  const [generationSuccess, setGenerationSuccess] = useState('');
  const [generatedAvailabilityIds, setGeneratedAvailabilityIds] = useState(
    () => new Set(),
  );
  const generationLockRef = useRef(false);

  // Schedule viewer state
  const [availableSlots, setAvailableSlots] = useState([]);
  const [bookedAppointments, setBookedAppointments] = useState([]);
  const [appointmentsSupported, setAppointmentsSupported] = useState(false);
  const [scheduleLoading, setScheduleLoading] = useState(false);
  const [scheduleError, setScheduleError] = useState('');
  const [scheduleDateFilter, setScheduleDateFilter] = useState('');
  const [refreshIndex, setRefreshIndex] = useState(0);

  // Load doctors list in Admin mode
  useEffect(() => {
    if (!isDoctorMode) {
      let ignore = false;
      async function fetchDoctors() {
        setDoctorsLoading(true);
        try {
          const res = await api.get('/doctors');
          if (!ignore && Array.isArray(res.data)) {
            setDoctorsList(res.data);
            setSelectedDoctorId((prev) => prev || (res.data.length > 0 ? String(res.data[0].id) : ''));
          }
        } catch (err) {
          if (!ignore) {
            console.error('Failed to load doctors list:', err);
          }
        } finally {
          if (!ignore) {
            setDoctorsLoading(false);
          }
        }
      }
      fetchDoctors();
      return () => {
        ignore = true;
      };
    }
  }, [isDoctorMode]);

  // Load schedule when activeDoctorId or refreshIndex changes
  useEffect(() => {
    let ignore = false;

    async function loadScheduleData() {
      if (!activeDoctorId) {
        if (!ignore) {
          setAvailableSlots([]);
          setBookedAppointments([]);
          setAppointmentsSupported(false);
        }
        return;
      }

      setScheduleLoading(true);
      setScheduleError('');

      try {
        const slotsReq = api.get('/appointment-slots/available', {
          params: { doctorId: activeDoctorId },
        });
        const apptsReq = api.get('/appointments').catch(() => null);

        const [slotsRes, apptsRes] = await Promise.all([slotsReq, apptsReq]);

        if (ignore) return;

        const loadedSlots = Array.isArray(slotsRes?.data) ? slotsRes.data : [];
        setAvailableSlots(loadedSlots);

        if (apptsRes && Array.isArray(apptsRes.data)) {
          const docAppts = apptsRes.data.filter(
            (a) =>
              String(a.doctor?.id) === String(activeDoctorId) &&
              ['CONFIRMED', 'COMPLETED'].includes(a.status),
          );
          setBookedAppointments(docAppts);
          setAppointmentsSupported(true);
        } else {
          setBookedAppointments([]);
          setAppointmentsSupported(false);
        }
      } catch (err) {
        if (ignore) return;
        setScheduleError(
          err.response?.data?.message || 'Failed to load schedule for doctor.',
        );
        setAvailableSlots([]);
        setBookedAppointments([]);
        setAppointmentsSupported(false);
      } finally {
        if (!ignore) {
          setScheduleLoading(false);
        }
      }
    }

    loadScheduleData();

    return () => {
      ignore = true;
    };
  }, [activeDoctorId, refreshIndex]);

  // Form validation for availability creation
  const validateForm = () => {
    if (!activeDoctorId) {
      return 'Please select a doctor.';
    }
    if (!formDate) {
      return 'Please choose a date.';
    }
    if (formDate < getTodayIso()) {
      return 'Availability date cannot be in the past.';
    }
    if (!formStartTime || !formEndTime) {
      return 'Please specify both start time and end time.';
    }

    const startMinutes = parseTimeToMinutes(formStartTime);
    const endMinutes = parseTimeToMinutes(formEndTime);

    if (startMinutes >= endMinutes) {
      return 'Start time must be before end time.';
    }

    const durationMinutes = endMinutes - startMinutes;
    if (durationMinutes < 30) {
      return 'Availability duration must be at least 30 minutes.';
    }

    return null;
  };

  // Handle Availability Submission
  const handleCreateAvailability = async (e) => {
    e.preventDefault();
    setFormError('');
    setFormSuccess('');

    const validationErr = validateForm();
    if (validationErr) {
      setFormError(validationErr);
      return;
    }

    setFormSubmitting(true);
    const draft = { doctorId: activeDoctorId, date: formDate, startTime: formStartTime, endTime: formEndTime };
    formDrafts.save(user.id, 'availability', draft);
    try {
      const payload = {
        doctorId: Number(activeDoctorId),
        date: formDate,
        startTime:
          formStartTime.length === 5 ? `${formStartTime}:00` : formStartTime,
        endTime: formEndTime.length === 5 ? `${formEndTime}:00` : formEndTime,
      };

      const response = await api.post('/doctor-availability', payload);
      const createdAvail = requireAvailabilityResult(response, payload);
      formDrafts.discard(user.id, 'availability', draft);

      setFormSuccess(
        `Availability created successfully for ${formatDate(
          createdAvail.date,
        )} (${formatTime(createdAvail.startTime)} - ${formatTime(
          createdAvail.endTime,
        )})! You can now generate appointment slots below.`,
      );

      // Prepend to recent session availabilities
      setRecentAvailabilities((prev) => [createdAvail, ...prev]);

      // Trigger schedule refresh
      setRefreshIndex((idx) => idx + 1);
    } catch (err) {
      setFormError(mutationErrorMessage(err,
        err.response?.data?.error || 'Failed to create availability. Please verify inputs and permissions.',
      ));
    } finally {
      setFormSubmitting(false);
    }
  };

  // Handle Slot Generation
  const handleGenerateSlots = async (avail) => {
    if (!avail || !avail.id) return;

    // Defense-in-depth duplicate generation guard
    if (generationLockRef.current) {
      return;
    }
    if (generatedAvailabilityIds.has(avail.id)) {
      setGenerationError(
        `Slots for availability #${avail.id} have already been generated in this session.`,
      );
      return;
    }

    generationLockRef.current = true;
    setIsGeneratingId(avail.id);
    setGenerationError('');
    setGenerationSuccess('');

    try {
      const response = await api.post(`/appointment-slots/${avail.id}/generate`);
      const slots = requireSlotGenerationResult(response, avail);

      // Register availability ID as generated
      setGeneratedAvailabilityIds((prev) => new Set([...prev, avail.id]));

      setGenerationSuccess(
        `Successfully generated ${slots.length} appointment slots for ${formatDate(
          avail.date,
        )} (${formatTime(avail.startTime)} - ${formatTime(avail.endTime)})!`,
      );

      // Trigger schedule refresh
      setRefreshIndex((idx) => idx + 1);

      // Trigger optional callback
      if (typeof onSlotsGenerated === 'function') {
        onSlotsGenerated(slots);
      }
    } catch (err) {
      setGenerationError(mutationErrorMessage(err, 'Failed to generate appointment slots. Please try again.'));
    } finally {
      generationLockRef.current = false;
      setIsGeneratingId(null);
    }
  };

  // Active doctor display information
  const activeDoctorInfo = useMemo(() => {
    if (isDoctorMode) {
      if (doctor && typeof doctor === 'object') {
        return `Dr. ${doctor.firstName || ''} ${doctor.lastName || ''} (ID: ${
          doctor.id || doctorId
        })`;
      }
      return doctor ? String(doctor) : `Doctor ID: ${doctorId}`;
    }
    const found = doctorsList.find((d) => String(d.id) === String(activeDoctorId));
    if (found) {
      return `Dr. ${found.firstName} ${found.lastName} (ID: ${found.id})`;
    }
    return activeDoctorId ? `Doctor ID: ${activeDoctorId}` : 'None Selected';
  }, [isDoctorMode, doctor, doctorId, doctorsList, activeDoctorId]);

  // Selected doctor object for displaying specialization & qualification
  const selectedDoctorObj = useMemo(() => {
    if (isDoctorMode) {
      if (doctor && typeof doctor === 'object') {
        return doctor;
      }
      return null;
    }
    return doctorsList.find((d) => String(d.id) === String(activeDoctorId)) || null;
  }, [isDoctorMode, doctor, doctorsList, activeDoctorId]);

  const specializationName = selectedDoctorObj?.specialization?.name || '';
  const qualification = selectedDoctorObj?.qualification || '';

  // Aggregate slots by date for schedule display
  const scheduleByDate = useMemo(() => {
    const map = new Map();

    // 1. Add available slots
    availableSlots.forEach((slot) => {
      const d = slot.date;
      if (!map.has(d)) {
        map.set(d, { date: d, available: [], booked: [] });
      }
      map.get(d).available.push(slot);
    });

    // 2. Add booked appointment slots (if supported by backend data)
    bookedAppointments.forEach((appt) => {
      if (appt.slot) {
        const d = appt.slot.date;
        if (!map.has(d)) {
          map.set(d, { date: d, available: [], booked: [] });
        }
        map.get(d).booked.push(appt);
      }
    });

    // Sort by date ascending
    const sorted = Array.from(map.values()).sort((a, b) =>
      a.date.localeCompare(b.date),
    );

    // Apply optional date filter
    if (scheduleDateFilter) {
      return sorted.filter((group) => group.date === scheduleDateFilter);
    }

    return sorted;
  }, [availableSlots, bookedAppointments, scheduleDateFilter]);

  // Total summary counts
  const totalAvailableCount = availableSlots.length;
  const totalConfirmedCount = appointmentsSupported
    ? bookedAppointments.filter((appt) => appt.status === 'CONFIRMED').length
    : 0;
  const totalCompletedCount = appointmentsSupported
    ? bookedAppointments.filter((appt) => appt.status === 'COMPLETED').length
    : 0;

  const handleManualRefresh = useCallback(() => {
    setRefreshIndex((idx) => idx + 1);
  }, []);

  return (
    <div className="availability-manager">
      {/* SECTION 1: Doctor Context & Availability Form */}
      <section className="am-card">
        <div className="am-card-header">
          <div>
            <h2 className="am-card-title">Doctor Availability Management</h2>
            <p className="am-card-subtitle">
              Configure working hours and publish 30-minute bookable slots.
            </p>
          </div>
          <div className="am-badge am-badge-success">
            {isDoctorMode ? 'Doctor Portal' : 'Admin Portal'}
          </div>
        </div>

        {/* Doctor Selector */}
        <div className="am-form-field am-doctor-selector-field">
          <label className="am-label" htmlFor="am-doctor-select">
            Selected Doctor:
          </label>
          {isDoctorMode ? (
            <input
              id="am-doctor-select"
              type="text"
              className="am-input am-doctor-select"
              value={activeDoctorInfo}
              disabled
              readOnly
              style={{ width: '100%', maxWidth: '100%', boxSizing: 'border-box' }}
            />
          ) : (
            <select
              id="am-doctor-select"
              className="am-select am-doctor-select"
              value={selectedDoctorId}
              onChange={(e) => setSelectedDoctorId(e.target.value)}
              disabled={doctorsLoading || formSubmitting}
              style={{ width: '100%', maxWidth: '100%', boxSizing: 'border-box' }}
            >
              {doctorsLoading ? (
                <option value="">Loading doctors...</option>
              ) : doctorsList.length === 0 ? (
                <option value="">No doctors found</option>
              ) : (
                doctorsList.map((doc) => (
                  <option key={doc.id} value={doc.id}>
                    Dr. {doc.firstName} {doc.lastName} (ID: {doc.id})
                  </option>
                ))
              )}
            </select>
          )}

          {/* Doctor Specialization & Qualification readable text */}
          {(specializationName || qualification) && (
            <div className="am-doctor-details">
              {specializationName && (
                <span className="am-doctor-spec">
                  <span className="am-doctor-detail-label">Specialization:</span>{' '}
                  <span className="am-doctor-detail-val">{specializationName}</span>
                </span>
              )}
              {specializationName && qualification && (
                <span className="am-doctor-detail-divider">•</span>
              )}
              {qualification && (
                <span className="am-doctor-qual">
                  <span className="am-doctor-detail-label">Qualification:</span>{' '}
                  <span className="am-doctor-detail-val">{qualification}</span>
                </span>
              )}
            </div>
          )}
        </div>

        {/* Availability Form */}
        <form onSubmit={handleCreateAvailability} className="am-form-layout" noValidate>
          <div className="am-form-grid">
            <div className="am-form-field">
              <label className="am-label" htmlFor="am-avail-date">
                Date *
              </label>
              <input
                id="am-avail-date"
                type="date"
                className="am-input"
                min={getTodayIso()}
                value={formDate}
                onChange={(e) => setFormDate(e.target.value)}
                disabled={formSubmitting || !activeDoctorId}
                required
              />
              <span className="am-field-hint">Must be today or in the future</span>
            </div>

            <div className="am-form-field">
              <label className="am-label" htmlFor="am-start-time">
                Start Time *
              </label>
              <input
                id="am-start-time"
                type="time"
                step="1800"
                className="am-input"
                value={formStartTime}
                onChange={(e) => setFormStartTime(e.target.value)}
                disabled={formSubmitting || !activeDoctorId}
                required
              />
              <span className="am-field-hint">e.g., 09:00 AM</span>
            </div>

            <div className="am-form-field">
              <label className="am-label" htmlFor="am-end-time">
                End Time *
              </label>
              <input
                id="am-end-time"
                type="time"
                step="1800"
                className="am-input"
                value={formEndTime}
                onChange={(e) => setFormEndTime(e.target.value)}
                disabled={formSubmitting || !activeDoctorId}
                required
              />
              <span className="am-field-hint">Minimum 30 minutes after start</span>
            </div>
          </div>

          {/* Form Feedback */}
          {formError && (
            <div className="am-alert am-alert-error" role="alert">
              <strong className="am-alert-label">Error:</strong>{' '}
              <span className="am-alert-message">{formError}</span>
            </div>
          )}

          {formSuccess && (
            <div className="am-alert am-alert-success" role="alert">
              <strong className="am-alert-label">Success:</strong>{' '}
              <span className="am-alert-message">{formSuccess}</span>
            </div>
          )}

          <div>
            <button
              type="submit"
              className="am-btn am-btn-primary"
              disabled={formSubmitting || !activeDoctorId}
            >
              {formSubmitting ? 'Creating Availability...' : 'Create Availability Window'}
            </button>
          </div>
        </form>
      </section>

      {/* SECTION 2: Slot Generation Actions */}
      {recentAvailabilities.length > 0 && (
        <section className="am-card">
          <div className="am-card-header">
            <div>
              <h2 className="am-card-title">Generated / Pending Availabilities</h2>
              <p className="am-card-subtitle">
                Generate 30-minute appointment slots for newly created availability records.
              </p>
            </div>
          </div>

          {generationError && (
            <div className="am-alert am-alert-error" role="alert">
              <strong className="am-alert-label">Generation Error:</strong>{' '}
              <span className="am-alert-message">{generationError}</span>
            </div>
          )}

          {generationSuccess && (
            <div className="am-alert am-alert-success" role="alert">
              <strong className="am-alert-label">Success:</strong>{' '}
              <span className="am-alert-message">{generationSuccess}</span>
            </div>
          )}

          <div className="am-avail-list">
            {recentAvailabilities.map((avail) => {
              const isAlreadyGenerated = generatedAvailabilityIds.has(avail.id);
              const isCurrentlyGenerating = isGeneratingId === avail.id;

              return (
                <div key={avail.id} className="am-avail-item">
                  <div className="am-avail-info">
                    <span className="am-avail-title">
                      {formatDate(avail.date)} • {formatTime(avail.startTime)} –{' '}
                      {formatTime(avail.endTime)}
                    </span>
                    <div className="am-avail-meta">
                      <span>Availability ID: #{avail.id}</span>
                      <span>
                        Doctor ID: {avail.doctor?.id || activeDoctorId}
                      </span>
                    </div>
                  </div>

                  <div className="am-avail-actions">
                    {isAlreadyGenerated ? (
                      <span className="am-badge am-badge-success">
                        Slots Generated
                      </span>
                    ) : (
                      <button
                        type="button"
                        className="am-btn am-btn-primary"
                        onClick={() => handleGenerateSlots(avail)}
                        disabled={
                          isCurrentlyGenerating ||
                          Boolean(isGeneratingId) ||
                          isAlreadyGenerated
                        }
                      >
                        {isCurrentlyGenerating
                          ? 'Generating Slots...'
                          : 'Generate 30-min Slots'}
                      </button>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        </section>
      )}

      {/* SECTION 3: Slot Schedule & Metrics */}
      <section className="am-card">
        <div className="am-card-header">
          <div>
            <h2 className="am-card-title">Appointment Slot Schedule</h2>
            <p className="am-card-subtitle">
              Overview of available, confirmed, and completed slots for {activeDoctorInfo}.
            </p>
          </div>
          <button
            type="button"
            className="am-btn am-btn-secondary"
            onClick={handleManualRefresh}
            disabled={scheduleLoading || !activeDoctorId}
          >
            {scheduleLoading ? 'Refreshing...' : 'Refresh Schedule'}
          </button>
        </div>

        {/* Metrics Grid */}
        <div className="am-metrics-grid">
          <div className="am-metric-card">
            <span className="am-metric-label">Available Slots</span>
            <span className="am-metric-val">{totalAvailableCount}</span>
          </div>

          {appointmentsSupported ? (
            <>
              <div className="am-metric-card">
                <span className="am-metric-label">Confirmed Bookings</span>
                <span className="am-metric-val">{totalConfirmedCount}</span>
              </div>
              <div className="am-metric-card">
                <span className="am-metric-label">Completed Appointments</span>
                <span className="am-metric-val">{totalCompletedCount}</span>
              </div>
            </>
          ) : (
            <div className="am-metric-card">
              <span className="am-metric-label">Schedule Mode</span>
              <span
                className="am-metric-val"
                style={{ fontSize: '18px', fontWeight: 600, color: 'var(--text)' }}
              >
                Available Only
              </span>
            </div>
          )}

          <div className="am-metric-card">
            <span className="am-metric-label">Scheduled Dates</span>
            <span className="am-metric-val">{scheduleByDate.length}</span>
          </div>
        </div>

        {/* Filter Toolbar */}
        <div className="am-schedule-toolbar">
          <div className="am-schedule-filter">
            <label className="am-label" htmlFor="am-date-filter">
              Filter by Date:
            </label>
            <input
              id="am-date-filter"
              type="date"
              className="am-input"
              style={{ width: 'auto' }}
              value={scheduleDateFilter}
              onChange={(e) => setScheduleDateFilter(e.target.value)}
            />
            {scheduleDateFilter && (
              <button
                type="button"
                className="am-btn am-btn-secondary"
                onClick={() => setScheduleDateFilter('')}
                style={{ padding: '6px 12px', fontSize: '12px' }}
              >
                Clear
              </button>
            )}
          </div>
        </div>

        {/* Schedule Grid Content */}
        {scheduleLoading ? (
          <div className="am-empty">Loading appointment slots...</div>
        ) : scheduleError ? (
          <div className="am-alert am-alert-error" role="alert">
            <strong className="am-alert-label">Error:</strong>{' '}
            <span className="am-alert-message">{scheduleError}</span>
          </div>
        ) : scheduleByDate.length === 0 ? (
          <div className="am-empty">
            No appointment slots scheduled for this doctor
            {scheduleDateFilter ? ` on ${formatDate(scheduleDateFilter)}` : ''}.
            Create an availability window above to generate slots.
          </div>
        ) : (
          <div>
            {scheduleByDate.map((group) => (
              <div key={group.date} className="am-slots-date-group">
                <div className="am-date-heading">
                  <span>{formatDate(group.date)}</span>
                  <span
                    style={{
                      fontSize: '12px',
                      color: 'var(--text)',
                      fontWeight: 400,
                    }}
                  >
                    ({group.available.length} available
                    {appointmentsSupported
                      ? `, ${group.booked.length} occupied`
                      : ''}
                    )
                  </span>
                </div>

                <div className="am-slots-grid">
                  {/* Render Available Slots */}
                  {group.available.map((slot) => (
                    <div
                      key={`avail-${slot.id}`}
                      className="am-slot-pill am-slot-available"
                      title={`Slot ID #${slot.id}`}
                    >
                      <span>
                        {formatTime(slot.startTime)} – {formatTime(slot.endTime)}
                      </span>
                      <span className="am-pill-badge">Available</span>
                    </div>
                  ))}

                  {/* Render occupied slots from confirmed or completed appointments */}
                  {group.booked.map((appt) => {
                    const isCompleted = appt.status === 'COMPLETED';

                    return (
                      <div
                        key={`booked-${appt.id}`}
                        className={`am-slot-pill ${
                          isCompleted ? 'am-slot-completed' : 'am-slot-booked'
                        }`}
                        title={`Appointment #${appt.id} • Patient: ${
                          appt.patient
                            ? `${appt.patient.firstName} ${appt.patient.lastName}`
                            : 'Patient'
                        } • Status: ${appt.status}${
                          appt.reason ? ` • Reason: ${appt.reason}` : ''
                        }`}
                      >
                        <span>
                          {formatTime(appt.slot?.startTime)} –{' '}
                          {formatTime(appt.slot?.endTime)}
                        </span>
                        <span className="am-pill-badge">
                          {isCompleted ? 'Completed' : 'Confirmed'}
                        </span>
                      </div>
                    );
                  })}
                </div>
              </div>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}
