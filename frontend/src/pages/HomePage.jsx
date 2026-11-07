import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import api from '../api/axios';
import DiscoverySearchForm from '../components/DiscoverySearchForm';
import { PublicFooter, PublicHeader } from '../components/PublicLayout';
import heroImage from '../assets/discovery/hero-consultation.png';
import heartIcon from '../assets/discovery/specialty-heart.svg';
import arrowIcon from '../assets/discovery/arrow-right.svg';
import './PublicPages.css';

function readSpecializations(data) {
  if (!Array.isArray(data) || data.some((item) => !Number.isFinite(item?.id) || typeof item?.name !== 'string')) {
    throw new Error('Unexpected specialization response.');
  }
  return data;
}

export default function HomePage() {
  const navigate = useNavigate();
  const [specializations, setSpecializations] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    let active = true;
    api.get('/specializations').then((response) => {
      if (active) setSpecializations(readSpecializations(response.data));
    }).catch(() => {
      if (active) setError('Specialties could not be loaded right now. Please try again later.');
    }).finally(() => {
      if (active) setLoading(false);
    });
    return () => { active = false; };
  }, []);

  const openDiscovery = (search) => {
    const params = new URLSearchParams();
    if (search.city.trim()) params.set('city', search.city.trim());
    if (search.specializationId) params.set('specializationId', search.specializationId);
    if (search.mode) params.set('mode', search.mode);
    navigate(`/discover${params.size ? `?${params}` : ''}`);
  };

  return (
    <div className="hms-public">
      <PublicHeader />
      <main>
        <section className="home-hero"><div className="hms-container home-hero__content">
          <div className="home-hero__copy"><span className="eyebrow">HMS · HEALTHCARE DISCOVERY</span><h1>Find the right doctor.<br />Get the care you deserve.</h1><p>Discover verified doctors and trusted clinics across India.</p></div>
          <img className="home-hero__image" src={heroImage} alt="Doctor speaking with a patient" />
          <div className="home-hero__search"><DiscoverySearchForm initialSearch={{ city: '', specializationId: '', mode: '' }} specializations={specializations} onSubmit={openDiscovery} /></div>
        </div></section>
        <section className="hms-section"><div className="hms-container"><SectionHeading eyebrow="EXPLORE CARE" title="Popular specialties" description="Choose the right type of care for your needs." />
          {loading && <p className="page-note" role="status">Loading specialties…</p>}
          {error && <p className="page-error" role="alert">{error}</p>}
          {!loading && !error && <div className="specialty-grid">{specializations.map((specialty) => <button className="specialty-tile" type="button" key={specialty.id} onClick={() => openDiscovery({ city: '', specializationId: String(specialty.id), mode: '' })}><span className="specialty-tile__icon"><img src={heartIcon} alt="" /></span><img className="specialty-tile__arrow" src={arrowIcon} alt="" /><strong>{specialty.name}</strong></button>)}</div>}
        </div></section>
        <section className="hms-section hms-section--tinted"><div className="hms-container"><SectionHeading eyebrow="THREE SIMPLE STEPS" title="How it works" description="From care to confirming your visit." /><div className="steps-grid"><InfoCard title="01 · Search doctors" copy="Choose a city, specialty and consultation mode." /><InfoCard title="02 · Compare clinics and availability" copy="Review verified clinic and appointment details when listings are available." /><InfoCard title="03 · Book your consultation" copy="Select a time and confirm the practice you want to visit." /></div></div></section>
        <section className="hms-section"><div className="hms-container"><SectionHeading eyebrow="CARE ON YOUR TERMS" title="Consultation options" description="Choose the format that suits your needs." /><div className="consultation-grid"><InfoCard title="In-clinic consultation" copy="Visit a nearby clinic. Compare fees and available times when public listings are available." action="Explore clinics" onAction={() => navigate('/discover?mode=IN_CLINIC')} /><InfoCard title="Video consultation" copy="Speak with a clinician remotely when a video consultation is available." action="Explore video visits" onAction={() => navigate('/discover?mode=VIDEO')} elevated /></div></div></section>
        <section className="hms-section hms-section--tinted"><div className="hms-container"><SectionHeading eyebrow="PUBLIC DISCOVERY" title="Featured doctors and clinics" description="Verified doctor and clinic cards will appear here when the public discovery service is available." /><UnavailableState /></div></section>
      </main>
      <PublicFooter />
    </div>
  );
}

export function SectionHeading({ eyebrow, title, description }) { return <div className="section-heading">{eyebrow && <span className="eyebrow">{eyebrow}</span>}<h2>{title}</h2><p>{description}</p></div>; }
export function InfoCard({ title, copy, action, onAction, elevated = false }) { return <article className={`info-card ${elevated ? 'info-card--elevated' : ''}`}><h3>{title}</h3><p>{copy}</p>{action && <button type="button" onClick={onAction}>{action}</button>}</article>; }
export function UnavailableState() { return <section className="unavailable-state"><span aria-hidden="true">⌕</span><div><h3>Doctor listings are not available yet.</h3><p>Verified doctor and clinic listings will appear here once the public discovery service is available.</p></div></section>; }
