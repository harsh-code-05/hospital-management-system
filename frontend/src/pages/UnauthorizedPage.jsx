import { Link } from 'react-router-dom';

export default function UnauthorizedPage() {
  return (
    <div>
      <h1>403 — Unauthorized</h1>
      <p>You do not have permission to access this page.</p>
      <Link to="/">← Back to Home</Link>
    </div>
  );
}
