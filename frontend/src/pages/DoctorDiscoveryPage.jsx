import { useEffect, useState, useCallback } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import api from '../api/axios';
import { useAuth } from '../context/useAuth';
import { RequestSequencer, executeSequencedRequest } from '../utils/requestSequencer';
import DiscoverySearchForm from '../components/DiscoverySearchForm';
import { PublicFooter, PublicHeader } from '../components/PublicLayout';
import { SectionHeading } from './HomePage';
import './PublicPages.css';

const FILTER_DEFAULTS = { city: '', specializationId: '', mode: '', date: '', fee: '', experience: '', clinic: '', gender: '' };
const MODES = [['', 'Any consultation'], ['IN_CLINIC', 'In-clinic'], ['VIDEO', 'Video consultation']];

function asFilterState(params) {
  return { ...FILTER_DEFAULTS, ...Object.fromEntries([...params.entries()].filter(([key]) => key in FILTER_DEFAULTS)) };
}

function toQuery(filters) {
  const next = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) => {
    if (value) next.set(key, value);
  });
  return next;
}

export default function DoctorDiscoveryPage() {
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  let user = null;
  try {
    const auth = useAuth();
    user = auth?.user;
  } catch {
    user = null;
  }

  const [specializations, setSpecializations] = useState([]);
  const [specLoading, setSpecLoading] = useState(true);
  const [specError, setSpecError] = useState('');
  const [drawerOpen, setDrawerOpen] = useState(false);

  const [doctors, setDoctors] = useState([]);
  const [doctorsLoading, setDoctorsLoading] = useState(true);
  const [doctorsError, setDoctorsError] = useState('');
  const [sequencer] = useState(() => (typeof RequestSequencer === 'function' ? new RequestSequencer() : null));

  const filters = asFilterState(params);

  useEffect(() => {
    sequencer?.mount();
    return () => sequencer?.dispose();
  }, [sequencer]);

  useEffect(() => {
    let active = true;
    api.get('/specializations')
      .then((response) => {
        if (!Array.isArray(response.data)) throw new Error('Unexpected specialization response.');
        if (active) setSpecializations(response.data.filter((item) => Number.isFinite(item?.id) && typeof item?.name === 'string'));
      })
      .catch(() => {
        if (active) setSpecError('Specialties could not be loaded right now. Please try again later.');
      })
      .finally(() => {
        if (active) setSpecLoading(false);
      });
    return () => {
      active = false;
    };
  }, []);

  const fetchDoctors = useCallback(() => {
    const queryParams = {};
    if (filters.specializationId) {
      queryParams.specializationId = filters.specializationId;
    }
    if (filters.city && filters.city.trim()) {
      queryParams.city = filters.city.trim();
    }
    if (filters.mode) {
      queryParams.mode = filters.mode;
    }

    if (typeof executeSequencedRequest === 'function' && sequencer) {
      return executeSequencedRequest(
        sequencer,
        async (signal) => {
          const response = await api.get('/v2/discovery/doctors', { params: queryParams, signal });
          if (!Array.isArray(response.data)) {
            throw new Error('Unexpected discovery response.');
          }
          return response.data;
        },
        {
          onStart: () => {
            setDoctorsLoading(true);
            setDoctorsError('');
          },
          onSuccess: (data) => {
            setDoctors(data);
            setDoctorsLoading(false);
            setDoctorsError('');
          },
          onError: (err) => {
            if (err?.name === 'CanceledError' || err?.code === 'ERR_CANCELED') {
              return;
            }
            setDoctorsError('Doctor listings could not be loaded right now. Please try again later.');
            setDoctorsLoading(false);
          },
        }
      );
    }

    setDoctorsLoading(true);
    setDoctorsError('');
    return api.get('/v2/discovery/doctors', { params: queryParams })
      .then((response) => {
        if (!Array.isArray(response.data)) throw new Error('Unexpected discovery response.');
        setDoctors(response.data);
        setDoctorsLoading(false);
      })
      .catch(() => {
        setDoctorsError('Doctor listings could not be loaded right now. Please try again later.');
        setDoctorsLoading(false);
      });
  }, [filters.specializationId, filters.city, filters.mode, sequencer]);

  useEffect(() => {
    fetchDoctors();
  }, [fetchDoctors]);

  const updateSearch = (search) => setParams(toQuery({ ...filters, ...search }));

  const handleBook = (doctor) => {
    const isPatient = user && user.role?.replace(/^ROLE_/i, '').toUpperCase() === 'PATIENT';
    const target = `/patient?doctorId=${doctor.doctorId}${doctor.specialization?.id ? `&specialtyId=${doctor.specialization.id}` : ''}`;
    if (isPatient) {
      navigate(target);
    } else {
      navigate(`/login?redirect=${encodeURIComponent(target)}`);
    }
  };

  const selectedSpecialty = specializations.find((item) => String(item.id) === filters.specializationId)?.name;
  const heading = selectedSpecialty
    ? `${selectedSpecialty} doctors${filters.city ? ` in ${filters.city}` : ''}`
    : 'Find doctors and clinics';
  const search = { city: params.get('city') || '', specializationId: params.get('specializationId') || '', mode: params.get('mode') || '' };

  const displayedDoctors = doctors.filter((doc) => {
    if (filters.gender && filters.gender !== 'Any') {
      if (!doc.gender || doc.gender.toUpperCase() !== filters.gender.toUpperCase()) {
        return false;
      }
    }
    if (filters.clinic && filters.clinic.trim()) {
      const term = filters.clinic.trim().toLowerCase();
      const hasClinic = Array.isArray(doc.clinics) && doc.clinics.some((c) =>
        c.clinicName?.toLowerCase().includes(term) || c.city?.toLowerCase().includes(term)
      );
      if (!hasClinic) return false;
    }
    if (filters.experience && filters.experience !== 'Any experience') {
      const exp = doc.experience ?? 0;
      if (filters.experience === '0–5 years' && exp > 5) return false;
      if (filters.experience === '6–10 years' && (exp < 6 || exp > 10)) return false;
      if (filters.experience === '10+ years' && exp < 10) return false;
    }
    if (filters.fee && filters.fee !== 'Any fee') {
      const fees = [doc.startingInClinicFee, doc.videoConsultationFee].filter((f) => f != null && !Number.isNaN(Number(f)));
      if (fees.length > 0) {
        const minFee = Math.min(...fees.map(Number));
        if (filters.fee === 'Under ₹500' && minFee > 500) return false;
        if (filters.fee === '₹500–₹1,000' && (minFee < 500 || minFee > 1000)) return false;
        if (filters.fee === '₹1,000+' && minFee < 1000) return false;
      }
    }
    return true;
  });

  return (
    <div className="hms-public">
      <PublicHeader />
      <main className="discovery-page">
        <section className="discovery-hero">
          <div className="hms-container">
            <p className="breadcrumb">Home <span>/</span> Find doctors</p>
            <h1>{heading}</h1>
            <p className="discovery-hero__summary">
              Verified doctor listings, clinic practices, and appointment booking across India.
            </p>
            <DiscoverySearchForm key={params.toString()} compact initialSearch={search} specializations={specializations} onSubmit={updateSearch} />
          </div>
        </section>

        <section className="hms-container discovery-layout">
          <aside className="desktop-filters">
            <FilterPanel key={params.toString()} filters={filters} specializations={specializations} onApply={setParams} />
          </aside>

          <div className="discovery-results">
            <div className="discovery-results__top">
              <SectionHeading
                title={displayedDoctors.length ? `${displayedDoctors.length} verified doctor${displayedDoctors.length === 1 ? '' : 's'} available` : 'Matching doctors'}
                description="Explore credentials, clinic locations, and available consultation modes."
              />
              <button className="filter-toggle" type="button" onClick={() => setDrawerOpen(true)}>Filters</button>
            </div>

            {specError && <p className="page-error" role="alert">{specError}</p>}

            {doctorsLoading && (
              <div className="discovery-loading" role="status">
                <div className="discovery-spinner" />
                <p>Searching for verified doctors...</p>
              </div>
            )}

            {!doctorsLoading && doctorsError && (
              <div className="discovery-error" role="alert">
                <h3>Unable to load doctors</h3>
                <p>{doctorsError}</p>
                <button className="hms-button" type="button" onClick={fetchDoctors}>
                  Try Again
                </button>
              </div>
            )}

            {!doctorsLoading && !doctorsError && displayedDoctors.length === 0 && (
              <div className="discovery-empty">
                <span className="discovery-empty__icon" aria-hidden="true">⌕</span>
                <h3>No doctors found</h3>
                <p>We couldn't find any verified doctors matching your current filters. Try searching for a different city or specialty.</p>
                <button
                  className="hms-button hms-button--outline"
                  type="button"
                  onClick={() => setParams(toQuery(FILTER_DEFAULTS))}
                >
                  Clear All Filters
                </button>
              </div>
            )}

            {!doctorsLoading && !doctorsError && displayedDoctors.length > 0 && (
              <div className="doctor-cards-list">
                {displayedDoctors.map((doctor) => {
                  const displayName = doctor.displayName || `Dr. ${doctor.firstName} ${doctor.lastName}`;
                  const modes = doctor.consultationModes || [];
                  const startingFee = doctor.startingInClinicFee ?? doctor.videoConsultationFee;
                  return (
                    <article className="doctor-card" key={doctor.doctorId}>
                      <div className="doctor-card__avatar-col">
                        {doctor.profilePhotoUrl ? (
                          <img className="doctor-card__avatar" src={doctor.profilePhotoUrl} alt={displayName} />
                        ) : (
                          <div className="doctor-card__avatar-placeholder">Dr</div>
                        )}
                      </div>

                      <div className="doctor-card__info-col">
                        <div className="doctor-card__header">
                          <h3 className="doctor-card__name">{displayName}</h3>
                          <span className="doctor-card__verified" title="Verified Medical Practitioner">✓ Verified</span>
                        </div>

                        <p className="doctor-card__meta">
                          <span className="doctor-card__specialty">{doctor.specialization?.name || 'General Practitioner'}</span>
                          {doctor.qualification && <span> · {doctor.qualification}</span>}
                          {Number.isFinite(doctor.experience) && <span> · {doctor.experience} yrs exp</span>}
                        </p>

                        {doctor.bio && <p className="doctor-card__bio">{doctor.bio}</p>}

                        <div className="doctor-card__badges">
                          {modes.includes('IN_CLINIC') && (
                            <span className="mode-badge mode-badge--clinic">
                              In-Clinic{doctor.startingInClinicFee != null ? ` · ₹${doctor.startingInClinicFee}` : ''}
                            </span>
                          )}
                          {modes.includes('VIDEO') && (
                            <span className="mode-badge mode-badge--video">
                              Video Consult{doctor.videoConsultationFee != null ? ` · ₹${doctor.videoConsultationFee}` : ''}
                            </span>
                          )}
                        </div>

                        {Array.isArray(doctor.clinics) && doctor.clinics.length > 0 && (
                          <div className="doctor-card__clinics">
                            <strong>Practice Clinics:</strong>
                            <ul className="doctor-card__clinics-list">
                              {doctor.clinics.map((clinic) => (
                                <li key={clinic.id}>
                                  <strong>{clinic.clinicName}</strong>
                                  {clinic.locality ? ` — ${clinic.locality}, ` : ' — '}
                                  {clinic.city}
                                  {clinic.inClinicFee != null ? ` (₹${clinic.inClinicFee})` : ''}
                                </li>
                              ))}
                            </ul>
                          </div>
                        )}
                      </div>

                      <div className="doctor-card__actions-col">
                        {startingFee != null && (
                          <div className="doctor-card__fee-display">
                            <span className="doctor-card__fee-label">Consultation from</span>
                            <span className="doctor-card__fee-amount">₹{startingFee}</span>
                          </div>
                        )}
                        <button
                          type="button"
                          className="hms-button doctor-card__book-button"
                          onClick={() => handleBook(doctor)}
                        >
                          Book Appointment
                        </button>
                      </div>
                    </article>
                  );
                })}
              </div>
            )}
          </div>
        </section>

        {drawerOpen && (
          <div className="filter-drawer-backdrop" role="presentation">
            <aside className="filter-drawer" role="dialog" aria-modal="true" aria-label="Discovery filters">
              <div className="filter-drawer__head">
                <strong>Filters</strong>
                <button type="button" onClick={() => setDrawerOpen(false)} aria-label="Close filters">×</button>
              </div>
              <FilterPanel key={params.toString()} filters={filters} specializations={specializations} onApply={(next) => { setParams(next); setDrawerOpen(false); }} />
            </aside>
          </div>
        )}
      </main>
      <PublicFooter />
    </div>
  );
}

function FilterPanel({ filters, specializations, onApply }) {
  const [draft, setDraft] = useState(filters);
  const set = (key) => (event) => setDraft((current) => ({ ...current, [key]: event.target.value }));
  const clear = () => setDraft(FILTER_DEFAULTS);
  const submit = (event) => { event.preventDefault(); onApply(toQuery(draft)); };
  return (
    <form className="filter-panel" onSubmit={submit}>
      <div className="filter-panel__title">
        <h2>Filters</h2>
        <button type="button" onClick={clear}>Clear all</button>
      </div>
      <label>City<input value={draft.city} onChange={set('city')} placeholder="Enter a city" /></label>
      <label>
        Specialty
        <select value={draft.specializationId} onChange={set('specializationId')}>
          <option value="">Any specialty</option>
          {specializations.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
        </select>
      </label>
      <fieldset>
        <legend>Consultation mode</legend>
        {MODES.map(([value, label]) => (
          <label className="radio-row" key={value}>
            <input type="radio" name="mode" value={value} checked={draft.mode === value} onChange={set('mode')} />
            {label}
          </label>
        ))}
      </fieldset>
      <FilterSelect label="Experience" value={draft.experience} onChange={set('experience')} options={['Any experience', '0–5 years', '6–10 years', '10+ years']} />
      <FilterSelect label="Consultation fee" value={draft.fee} onChange={set('fee')} options={['Any fee', 'Under ₹500', '₹500–₹1,000', '₹1,000+']} />
      <label>Clinic or hospital<input value={draft.clinic} onChange={set('clinic')} placeholder="Search practice name" /></label>
      <FilterSelect label="Gender" value={draft.gender} onChange={set('gender')} options={['Any', 'Woman', 'Man', 'Prefer not to say']} />
      <button className="hms-button" type="submit">Apply filters</button>
    </form>
  );
}

function FilterSelect({ label, value, onChange, options }) {
  return (
    <label>
      {label}
      <select value={value} onChange={onChange}>
        {options.map((option) => (
          <option key={option} value={option === options[0] ? '' : option}>{option}</option>
        ))}
      </select>
    </label>
  );
}
