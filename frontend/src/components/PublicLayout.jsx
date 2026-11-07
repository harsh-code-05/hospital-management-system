import { Link, useMatch, useNavigate, useSearchParams } from 'react-router-dom';
import { useState } from 'react';
import logoMark from '../assets/discovery/hms-cross.svg';
import './PublicLayout.css';

export function HmsLogo({ reversed = false }) {
  return (
    <Link className={`hms-logo ${reversed ? 'hms-logo--reversed' : ''}`} to="/" aria-label="HMS home">
      <span className="hms-logo__mark"><img src={logoMark} alt="" /></span>
      <span className="hms-logo__words"><strong>HMS</strong><small>Healthcare discovery</small></span>
    </Link>
  );
}

export function PublicHeader() {
  const [open, setOpen] = useState(false);
  const navigate = useNavigate();
  const onDiscoveryPage = useMatch('/discover') !== null;
  const [searchParams] = useSearchParams();
  const isVideoMode = searchParams.get('mode') === 'VIDEO';
  const findDoctorsActive = onDiscoveryPage && !isVideoMode;
  const videoActive = onDiscoveryPage && isVideoMode;
  const close = () => setOpen(false);

  return (
    <header className="public-header">
      <div className="public-header__inner">
        <HmsLogo />
        <button className="public-header__menu" type="button" aria-expanded={open} aria-controls="public-navigation" onClick={() => setOpen((value) => !value)}>
          <span className="sr-only">{open ? 'Close navigation' : 'Open navigation'}</span><span aria-hidden="true">☰</span>
        </button>
        <nav id="public-navigation" className={`public-nav ${open ? 'public-nav--open' : ''}`} aria-label="Primary navigation">
          <Link
            to="/discover"
            className={findDoctorsActive ? 'active' : undefined}
            aria-current={findDoctorsActive ? 'page' : undefined}
            onClick={close}
          >
            Find Doctors
          </Link>
          <Link
            to="/discover?mode=VIDEO"
            className={videoActive ? 'active' : undefined}
            aria-current={videoActive ? 'page' : undefined}
            onClick={close}
          >
            Video Consultation
          </Link>
          <Link to="/login" onClick={close}>For Doctors</Link>
        </nav>
        <div className="public-header__actions">
          <span className="public-location" aria-label="Current city">⌾ Kanpur</span>
          <Link className="hms-button hms-button--outline" to="/login">Log In</Link>
          <button className="hms-button" type="button" onClick={() => navigate('/login')}>Sign Up</button>
        </div>
      </div>
    </header>
  );
}

export function PublicFooter() {
  return (
    <footer className="public-footer">
      <div className="public-footer__top">
        <div><HmsLogo reversed /><p>Healthcare discovery for India.</p></div>
        <div><strong>Explore</strong><Link to="/">About HMS</Link><Link to="/discover">Find Doctors</Link><Link to="/login">For Doctors</Link></div>
        <div><strong>Support</strong><span>Help and Support</span><span>Privacy Policy</span><span>Terms of Service</span></div>
      </div>
      <div className="public-footer__bottom">HMS · Healthcare Discovery Platform</div>
    </footer>
  );
}
