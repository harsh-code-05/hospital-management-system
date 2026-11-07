import { useState } from 'react';

const MODES = [{ value: '', label: 'Any consultation' }, { value: 'IN_CLINIC', label: 'In-clinic' }, { value: 'VIDEO', label: 'Video consultation' }];

export default function DiscoverySearchForm({ initialSearch, specializations, onSubmit, compact = false }) {
  const [search, setSearch] = useState(initialSearch);
  const update = (field) => (event) => setSearch((current) => ({ ...current, [field]: event.target.value }));
  const submit = (event) => { event.preventDefault(); onSubmit(search); };
  return (
    <form className={`discovery-search ${compact ? 'discovery-search--compact' : ''}`} onSubmit={submit}>
      <label>City<input value={search.city} onChange={update('city')} placeholder="Enter a city" /></label>
      <label>Specialty<select value={search.specializationId} onChange={update('specializationId')}><option value="">Choose a specialty</option>{specializations.map((specialization) => <option key={specialization.id} value={specialization.id}>{specialization.name}</option>)}</select></label>
      <label>Consultation mode<select value={search.mode} onChange={update('mode')}>{MODES.map((mode) => <option key={mode.value} value={mode.value}>{mode.label}</option>)}</select></label>
      <button className="hms-button" type="submit">{compact ? 'Edit Search' : 'Search Doctors'}</button>
    </form>
  );
}
