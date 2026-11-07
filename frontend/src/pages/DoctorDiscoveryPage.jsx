import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import api from '../api/axios';
import DiscoverySearchForm from '../components/DiscoverySearchForm';
import { PublicFooter, PublicHeader } from '../components/PublicLayout';
import { SectionHeading, UnavailableState } from './HomePage';
import './PublicPages.css';

const FILTER_DEFAULTS = { city: '', specializationId: '', mode: '', date: '', fee: '', experience: '', clinic: '', gender: '' };
const MODES = [['', 'Any consultation'], ['IN_CLINIC', 'In-clinic'], ['VIDEO', 'Video consultation']];
function asFilterState(params) { return { ...FILTER_DEFAULTS, ...Object.fromEntries([...params.entries()].filter(([key]) => key in FILTER_DEFAULTS)) }; }
function toQuery(filters) { const next = new URLSearchParams(); Object.entries(filters).forEach(([key, value]) => { if (value) next.set(key, value); }); return next; }

export default function DoctorDiscoveryPage() {
  const [params, setParams] = useSearchParams();
  const [specializations, setSpecializations] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [drawerOpen, setDrawerOpen] = useState(false);
  const filters = asFilterState(params);
  useEffect(() => { let active = true; api.get('/specializations').then((response) => { if (!Array.isArray(response.data)) throw new Error('Unexpected specialization response.'); if (active) setSpecializations(response.data.filter((item) => Number.isFinite(item?.id) && typeof item?.name === 'string')); }).catch(() => { if (active) setError('Specialties could not be loaded right now. Please try again later.'); }).finally(() => { if (active) setLoading(false); }); return () => { active = false; }; }, []);
  const updateSearch = (search) => setParams(toQuery({ ...filters, ...search }));
  const selectedSpecialty = specializations.find((item) => String(item.id) === filters.specializationId)?.name;
  const heading = selectedSpecialty ? `${selectedSpecialty} doctors${filters.city ? ` in ${filters.city}` : ''}` : 'Find doctors and clinics';
  const search = { city: params.get('city') || '', specializationId: params.get('specializationId') || '', mode: params.get('mode') || '' };
  return <div className="hms-public"><PublicHeader /><main className="discovery-page"><section className="discovery-hero"><div className="hms-container"><p className="breadcrumb">Home <span>/</span> Find doctors</p><h1>{heading}</h1><p className="discovery-hero__summary">Public verified doctor listings will appear here when the discovery service is available.</p><DiscoverySearchForm key={params.toString()} compact initialSearch={search} specializations={specializations} onSubmit={updateSearch} /></div></section><section className="hms-container discovery-layout"><aside className="desktop-filters"><FilterPanel key={params.toString()} filters={filters} specializations={specializations} onApply={setParams} /></aside><div className="discovery-results"><div className="discovery-results__top"><SectionHeading title="Matching doctors" description="Availability, clinics, fees and appointment slots require the public discovery service." /><button className="filter-toggle" type="button" onClick={() => setDrawerOpen(true)}>Filters</button></div>{loading && <p className="page-note" role="status">Loading specialties…</p>}{error && <p className="page-error" role="alert">{error}</p>}{!loading && !error && <UnavailableState />}</div></section>{drawerOpen && <div className="filter-drawer-backdrop" role="presentation"><aside className="filter-drawer" role="dialog" aria-modal="true" aria-label="Discovery filters"><div className="filter-drawer__head"><strong>Filters</strong><button type="button" onClick={() => setDrawerOpen(false)} aria-label="Close filters">×</button></div><FilterPanel key={params.toString()} filters={filters} specializations={specializations} onApply={(next) => { setParams(next); setDrawerOpen(false); }} /></aside></div>}</main><PublicFooter /></div>;
}

function FilterPanel({ filters, specializations, onApply }) {
  const [draft, setDraft] = useState(filters);
  const set = (key) => (event) => setDraft((current) => ({ ...current, [key]: event.target.value }));
  const clear = () => setDraft(FILTER_DEFAULTS);
  const submit = (event) => { event.preventDefault(); onApply(toQuery(draft)); };
  return <form className="filter-panel" onSubmit={submit}><div className="filter-panel__title"><h2>Filters</h2><button type="button" onClick={clear}>Clear all</button></div><label>City<input value={draft.city} onChange={set('city')} placeholder="Enter a city" /></label><label>Specialty<select value={draft.specializationId} onChange={set('specializationId')}><option value="">Any specialty</option>{specializations.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label><fieldset><legend>Consultation mode</legend>{MODES.map(([value, label]) => <label className="radio-row" key={value}><input type="radio" name="mode" value={value} checked={draft.mode === value} onChange={set('mode')} />{label}</label>)}</fieldset><label>Available date<input type="date" value={draft.date} onChange={set('date')} /></label><FilterSelect label="Consultation fee" value={draft.fee} onChange={set('fee')} options={['Any fee', 'Under ₹500', '₹500–₹1,000', '₹1,000+']} /><FilterSelect label="Experience" value={draft.experience} onChange={set('experience')} options={['Any experience', '0–5 years', '6–10 years', '10+ years']} /><label>Clinic or hospital<input value={draft.clinic} onChange={set('clinic')} placeholder="Search practice name" /></label><FilterSelect label="Gender" value={draft.gender} onChange={set('gender')} options={['Any', 'Woman', 'Man', 'Prefer not to say']} /><p className="filter-panel__notice">These filters are saved in your search, but public results cannot be filtered until the discovery API is available.</p><button className="hms-button" type="submit">Apply filters</button></form>;
}
function FilterSelect({ label, value, onChange, options }) { return <label>{label}<select value={value} onChange={onChange}>{options.map((option) => <option key={option} value={option === options[0] ? '' : option}>{option}</option>)}</select></label>; }
