import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import { transformWithOxc } from 'vite';

function componentSource(path) {
  return readFileSync(new URL(path, import.meta.url), 'utf8')
    .replace(/^import .*\r?\n/gm, '')
    .replace(/export (default )?function/g, 'function');
}

const { code: loginCode } = await transformWithOxc(
  componentSource('../src/pages/LoginPage.jsx'),
  'LoginPage.jsx',
  { jsx: { runtime: 'classic', pragma: 'h', pragmaFrag: 'Fragment' } }
);

function createLoginSandbox(overrides = {}) {
  const navigations = [];
  const context = vm.createContext({
    h: (type, props, ...children) => ({ type, props: props ?? {}, children }),
    Fragment: Symbol('Fragment'),
    useAuth: () => ({
      user: overrides.user ?? null,
      loading: overrides.loading ?? false,
      sessionExpired: false,
      refreshUser: async () => overrides.refreshedUser ?? overrides.user,
    }),
    useNavigate: () => (target, options) => {
      navigations.push({ target, options });
    },
    useSearchParams: () => [
      new URLSearchParams(overrides.searchParams ?? ''),
      () => {},
    ],
    useState: (initial) => [typeof initial === 'function' ? initial() : initial, () => {}],
    api: overrides.api ?? { post: async () => ({ data: {} }) },
    requireJsonResponse: () => {},
    mutationErrorMessage: (err, fallback) => fallback,
    Link: ({ to, children }) => ({ type: 'a', props: { href: to }, children }),
    Navigate: ({ to, replace }) => ({ type: 'Navigate', props: { to, replace } }),
    URLSearchParams,
  });

  const script = new vm.Script(`
    ${loginCode};
    ({ isValidPatientRedirect, getSafePatientRedirect, LoginPage });
  `);

  const exports = script.runInContext(context);
  return { ...exports, navigations, context };
}

// 1. Valid /patient redirect
test('1. isValidPatientRedirect allows valid /patient path', () => {
  const { isValidPatientRedirect } = createLoginSandbox();
  assert.equal(isValidPatientRedirect('/patient'), true);
});

// 2. Valid /patient?doctorId=42
test('2. isValidPatientRedirect allows /patient?doctorId=42', () => {
  const { isValidPatientRedirect } = createLoginSandbox();
  assert.equal(isValidPatientRedirect('/patient?doctorId=42'), true);
  assert.equal(isValidPatientRedirect('/patient?doctorId=42&specialtyId=1'), true);
});

// 3. Valid nested patient path
test('3. isValidPatientRedirect allows valid nested patient routes', () => {
  const { isValidPatientRedirect } = createLoginSandbox();
  assert.equal(isValidPatientRedirect('/patient/appointments'), true);
  assert.equal(isValidPatientRedirect('/patient/profile'), true);
});

// 4. /patient.evil.com rejection
test('4. isValidPatientRedirect rejects host-confusion /patient.evil.com', () => {
  const { isValidPatientRedirect } = createLoginSandbox();
  assert.equal(isValidPatientRedirect('/patient.evil.com'), false);
  assert.equal(isValidPatientRedirect('/patients'), false);
  assert.equal(isValidPatientRedirect('/patient-portal'), false);
});

// 5. /patient//evil.com rejection
test('5. isValidPatientRedirect rejects double slash paths /patient//evil.com', () => {
  const { isValidPatientRedirect } = createLoginSandbox();
  assert.equal(isValidPatientRedirect('/patient//evil.com'), false);
  assert.equal(isValidPatientRedirect('/patient//appointments'), false);
});

// 6. //evil.example rejection
test('6. isValidPatientRedirect rejects protocol-relative //evil.example', () => {
  const { isValidPatientRedirect } = createLoginSandbox();
  assert.equal(isValidPatientRedirect('//evil.example'), false);
  assert.equal(isValidPatientRedirect('//patient'), false);
});

// 7. Backslash-based path rejection
test('7. isValidPatientRedirect rejects backslash and percent-encoded backslash attempts', () => {
  const { isValidPatientRedirect } = createLoginSandbox();
  assert.equal(isValidPatientRedirect('/patient\\evil.com'), false);
  assert.equal(isValidPatientRedirect('/patient\\..\\evil.com'), false);
  assert.equal(isValidPatientRedirect('/patient%5cevil.com'), false);
  assert.equal(isValidPatientRedirect('/patient%5Cevil.com'), false);
});

// 8. Absolute external URL rejection
test('8. isValidPatientRedirect rejects absolute external URLs with schemes', () => {
  const { isValidPatientRedirect } = createLoginSandbox();
  assert.equal(isValidPatientRedirect('https://evil.example/patient'), false);
  assert.equal(isValidPatientRedirect('http://evil.example/patient'), false);
  assert.equal(isValidPatientRedirect('javascript:alert(1)'), false);
  assert.equal(isValidPatientRedirect('data:text/html,evil'), false);
});

// 9. ADMIN redirected to /admin
test('9. ADMIN user is redirected to /admin even if patient redirect is requested', () => {
  const { LoginPage } = createLoginSandbox({
    user: { id: 2, role: 'ADMIN' },
    searchParams: 'redirect=' + encodeURIComponent('/patient?doctorId=42'),
  });
  const vdom = LoginPage();
  assert.equal(vdom.props.to, '/admin', 'Admin must be navigated to /admin, never to patient redirect');
  assert.equal(vdom.props.replace, true);
});

// 10. DOCTOR redirected to /doctor
test('10. DOCTOR user is redirected to /doctor even if patient redirect is requested', () => {
  const { LoginPage } = createLoginSandbox({
    user: { id: 3, role: 'DOCTOR' },
    searchParams: 'redirect=' + encodeURIComponent('/patient?doctorId=42'),
  });
  const vdom = LoginPage();
  assert.equal(vdom.props.to, '/doctor', 'Doctor must be navigated to /doctor, never to patient redirect');
  assert.equal(vdom.props.replace, true);
});

test('authenticated PATIENT user follows valid patient redirect on mount', () => {
  const target = '/patient?doctorId=42&specialtyId=1';
  const { LoginPage } = createLoginSandbox({
    user: { id: 7, role: 'PATIENT' },
    searchParams: 'redirect=' + encodeURIComponent(target),
  });
  const vdom = LoginPage();
  assert.equal(vdom.props.to, target);
  assert.equal(vdom.props.replace, true);
});

test('authenticated PATIENT user falls back to /patient if redirect is invalid or malicious', () => {
  const { LoginPage } = createLoginSandbox({
    user: { id: 7, role: 'PATIENT' },
    searchParams: 'redirect=' + encodeURIComponent('/patient.evil.com'),
  });
  const vdom = LoginPage();
  assert.equal(vdom.props.to, '/patient', 'Malicious redirect must fall back to default patient dashboard');
  assert.equal(vdom.props.replace, true);
});
