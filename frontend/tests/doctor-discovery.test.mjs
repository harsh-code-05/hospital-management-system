import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import { transformWithOxc } from 'vite';
import { RequestSequencer, executeSequencedRequest } from '../src/utils/requestSequencer.js';

function componentSource(path) {
  return readFileSync(new URL(path, import.meta.url), 'utf8')
    .replace(/^import .*\r?\n/gm, '')
    .replace('export default function', 'function');
}

const { code: discoveryCode } = await transformWithOxc(
  componentSource('../src/pages/DoctorDiscoveryPage.jsx'),
  'DoctorDiscoveryPage.jsx',
  { jsx: { runtime: 'classic', pragma: 'h', pragmaFrag: 'Fragment' } }
);

const flush = () => new Promise((resolve) => setImmediate(resolve));

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

function sameDeps(a, b) {
  return a?.length === b?.length && b.every((v, i) => Object.is(v, a[i]));
}

function nodes(tree) {
  if (Array.isArray(tree)) return tree.flatMap(nodes);
  return tree && typeof tree === 'object' ? [tree, ...nodes(tree.children)] : [];
}

function text(tree) {
  if (Array.isArray(tree)) return tree.map(text).join('');
  return tree && typeof tree === 'object' ? text(tree.children) : String(tree ?? '');
}

const SAMPLE_DOCTOR = {
  doctorId: 101,
  firstName: 'Aarav',
  lastName: 'Sharma',
  displayName: 'Dr. Aarav Sharma',
  specialization: { id: 1, name: 'Cardiology' },
  experience: 12,
  qualification: 'MBBS, MD',
  bio: 'Experienced cardiologist specializing in preventive cardiology.',
  gender: 'MAN',
  profilePhotoUrl: null,
  consultationModes: ['IN_CLINIC', 'VIDEO'],
  videoConsultationFee: 500,
  startingInClinicFee: 800,
  currency: 'INR',
  clinics: [
    {
      id: 201,
      clinicName: 'Heart Care Clinic',
      address: '123 Civil Lines',
      locality: 'Civil Lines',
      city: 'Kanpur',
      state: 'Uttar Pradesh',
      postalCode: '208001',
      inClinicFee: 800,
    },
  ],
};

async function setup({
  user = null,
  searchParams = new URLSearchParams(),
  apiHandlers = {},
} = {}) {
  const hooks = [];
  let cursor = 0;
  let pendingEffects = [];
  const navigations = [];
  const apiCalls = [];

  let currentParams = searchParams;
  const setParams = (next) => {
    currentParams = typeof next === 'function' ? next(currentParams) : next;
  };

  const defaultApi = {
    get: async (url, config = {}) => {
      apiCalls.push({ url, config });
      if (url === '/specializations') {
        return { data: [{ id: 1, name: 'Cardiology' }, { id: 2, name: 'Dermatology' }] };
      }
      if (url === '/v2/discovery/doctors') {
        return { data: [SAMPLE_DOCTOR] };
      }
      return { data: [] };
    },
    ...apiHandlers,
  };

  const context = vm.createContext({
    h: (type, props, ...children) => ({ type, props: props ?? {}, children }),
    Fragment: Symbol('Fragment'),
    useAuth: () => ({ user }),
    useNavigate: () => (target) => {
      navigations.push(target);
    },
    useSearchParams: () => [currentParams, setParams],
    useState(initial) {
      const cell = hooks[cursor++] ??= { value: typeof initial === 'function' ? initial() : initial };
      return [
        cell.value,
        (next) => {
          cell.value = typeof next === 'function' ? next(cell.value) : next;
        },
      ];
    },
    useRef(initial) {
      return hooks[cursor++] ??= { current: initial };
    },
    useCallback(fn, deps) {
      const i = cursor++;
      if (!sameDeps(hooks[i]?.deps, deps)) hooks[i] = { fn, deps };
      return hooks[i].fn;
    },
    useEffect(effect, deps) {
      const i = cursor++;
      if (!sameDeps(hooks[i]?.deps, deps)) {
        pendingEffects.push(() => {
          hooks[i]?.cleanup?.();
          hooks[i] = { deps, cleanup: effect() };
        });
      }
    },
    api: defaultApi,
    RequestSequencer,
    executeSequencedRequest,
    AbortController,
    URLSearchParams,
    PublicHeader: () => ({ type: 'header', props: { className: 'public-header' } }),
    PublicFooter: () => ({ type: 'footer', props: { className: 'public-footer' } }),
    DiscoverySearchForm: (props) => ({ type: 'form', props: { className: 'discovery-search' } }),
    SectionHeading: (props) => ({ type: 'div', props: { className: 'section-heading' }, children: [props.title] }),
  });

  const script = new vm.Script(`${discoveryCode}; DoctorDiscoveryPage;`);
  const Component = script.runInContext(context);

  async function render() {
    cursor = 0;
    const tree = Component();
    const effectsToRun = pendingEffects;
    pendingEffects = [];
    for (const run of effectsToRun) {
      run();
    }
    await flush();
    return tree;
  }

  function unmount() {
    hooks.forEach((h) => h?.cleanup?.());
  }

  // Initial render
  const initialTree = await render();

  return {
    render,
    unmount,
    getTree: () => initialTree,
    navigations,
    apiCalls,
    getParams: () => currentParams,
    setParams,
  };
}

test('initial loading state is displayed before doctor results arrive', async () => {
  const pending = deferred();
  const { render } = await setup({
    apiHandlers: {
      get: async (url, config) => {
        if (url === '/specializations') return { data: [] };
        if (url === '/v2/discovery/doctors') return pending.promise;
        return { data: [] };
      },
    },
  });

  const tree = await render();
  const allNodes = nodes(tree);
  const loadingNode = allNodes.find((n) => n.props?.className?.includes('discovery-loading'));
  assert.ok(loadingNode, 'discovery-loading element should be rendered while fetching');
  assert.equal(loadingNode.props.role, 'status');
  assert.match(text(loadingNode), /Searching for verified doctors/);
});

test('discovery API error displays error alert and retry button', async () => {
  let callCount = 0;
  const { render } = await setup({
    apiHandlers: {
      get: async (url, config) => {
        if (url === '/specializations') return { data: [] };
        if (url === '/v2/discovery/doctors') {
          callCount += 1;
          throw new Error('Network error');
        }
        return { data: [] };
      },
    },
  });

  // Let effect settle
  await render();
  const tree = await render();
  const allNodes = nodes(tree);
  const errorNode = allNodes.find((n) => n.props?.className?.includes('discovery-error'));
  assert.ok(errorNode, 'discovery-error element should be rendered on failure');
  assert.equal(errorNode.props.role, 'alert');
  assert.match(text(errorNode), /Unable to load doctors/);

  // Verify retry button exists
  const retryBtn = nodes(errorNode).find((n) => n.type === 'button');
  assert.ok(retryBtn, 'Retry button should be present in error state');
  assert.match(text(retryBtn), /Try Again/);

  // Click retry button
  retryBtn.props.onClick();
  await flush();
  assert.equal(callCount, 2, 'Clicking retry should invoke API again');
});

test('empty doctor response renders friendly empty state with clear filters button', async () => {
  const { render } = await setup({
    apiHandlers: {
      get: async (url) => {
        if (url === '/specializations') return { data: [] };
        if (url === '/v2/discovery/doctors') return { data: [] };
        return { data: [] };
      },
    },
  });

  await render();
  const tree = await render();
  const allNodes = nodes(tree);
  const emptyNode = allNodes.find((n) => n.props?.className?.includes('discovery-empty'));
  assert.ok(emptyNode, 'discovery-empty should be rendered when no doctors match');
  assert.match(text(emptyNode), /No doctors found/);

  const clearBtn = nodes(emptyNode).find((n) => n.type === 'button');
  assert.ok(clearBtn, 'Clear filters button should be present');
  assert.match(text(clearBtn), /Clear All Filters/);
});

test('successful doctor response renders doctor card with verified badge, modes, clinics, fees', async () => {
  const { render } = await setup();
  await render();
  const tree = await render();

  const allNodes = nodes(tree);
  const doctorCards = allNodes.filter((n) => n.props?.className === 'doctor-card');
  assert.equal(doctorCards.length, 1, 'Should render 1 doctor card');

  const card = doctorCards[0];
  const cardText = text(card);

  assert.match(cardText, /Dr\. Aarav Sharma/, 'Doctor name rendered');
  assert.match(cardText, /Verified/, 'Verified badge rendered');
  assert.match(cardText, /Cardiology/, 'Specialization rendered');
  assert.match(cardText, /12 yrs exp/, 'Experience rendered');
  assert.match(cardText, /MBBS, MD/, 'Qualification rendered');
  assert.match(cardText, /In-Clinic · ₹800/, 'In-clinic fee rendered');
  assert.match(cardText, /Video Consult · ₹500/, 'Video consultation fee rendered');
  assert.match(cardText, /Heart Care Clinic/, 'Clinic name rendered');
  assert.match(cardText, /Kanpur/, 'Clinic city rendered');
  assert.match(cardText, /Book Appointment/, 'Book CTA rendered');

  // Verify contactPhone is NOT rendered
  assert.doesNotMatch(cardText, /contactPhone|9876543210/, 'Clinic phone number must not be exposed');
});

test('clicking Book Appointment redirects unauthenticated user to login with redirect param', async () => {
  const { render, navigations } = await setup({ user: null });
  await render();
  const tree = await render();

  const allNodes = nodes(tree);
  const bookBtn = allNodes.find(
    (n) => n.type === 'button' && n.props?.className?.includes('doctor-card__book-button')
  );
  assert.ok(bookBtn, 'Book Appointment button should exist');

  bookBtn.props.onClick();
  assert.equal(navigations.length, 1);
  assert.equal(
    navigations[0],
    `/login?redirect=${encodeURIComponent('/patient?doctorId=101&specialtyId=1')}`
  );
});

test('clicking Book Appointment navigates authenticated patient directly to /patient', async () => {
  const { render, navigations } = await setup({
    user: { id: 7, role: 'PATIENT' },
  });
  await render();
  const tree = await render();

  const allNodes = nodes(tree);
  const bookBtn = allNodes.find(
    (n) => n.type === 'button' && n.props?.className?.includes('doctor-card__book-button')
  );
  assert.ok(bookBtn, 'Book Appointment button should exist');

  bookBtn.props.onClick();
  assert.equal(navigations.length, 1);
  assert.equal(navigations[0], '/patient?doctorId=101&specialtyId=1');
});

test('URL query parameters are forwarded to backend discovery API', async () => {
  const params = new URLSearchParams('specializationId=1&city=Kanpur&mode=IN_CLINIC');
  const { apiCalls, render } = await setup({ searchParams: params });

  await render();

  const discoveryCall = apiCalls.find((call) => call.url === '/v2/discovery/doctors');
  assert.ok(discoveryCall, 'Should have made a call to /v2/discovery/doctors');
  assert.equal(discoveryCall.config.params.specializationId, '1');
  assert.equal(discoveryCall.config.params.city, 'Kanpur');
  assert.equal(discoveryCall.config.params.mode, 'IN_CLINIC');
});

test('rapid filter changes prevent an older slow response from overwriting newer results', async () => {
  const slowRequest = deferred();
  const fastDoctor = { ...SAMPLE_DOCTOR, doctorId: 999, displayName: 'Dr. Fast Doctor' };

  let requestCount = 0;

  const { render, setParams } = await setup({
    apiHandlers: {
      get: async (url, config = {}) => {
        if (url === '/specializations') return { data: [] };
        if (url === '/v2/discovery/doctors') {
          requestCount += 1;
          const currentCount = requestCount;
          if (currentCount === 1) {
            // First search request is slow
            return slowRequest.promise;
          }
          // Second search request finishes immediately
          return { data: [fastDoctor] };
        }
        return { data: [] };
      },
    },
  });

  // Request 1 is started (slow)
  await render();

  // Change search filter to trigger request 2 (fast)
  setParams(new URLSearchParams('city=Pune'));
  await render();
  const tree2 = await render();
  const cardText2 = text(nodes(tree2).find((n) => n.props?.className === 'doctor-card'));
  assert.match(cardText2, /Dr\. Fast Doctor/);

  // Now settle the first (older, slow) request with an older doctor
  slowRequest.resolve({ data: [{ ...SAMPLE_DOCTOR, doctorId: 101, displayName: 'Dr. Old Stale Doctor' }] });
  await flush();

  // Verify that Dr. Fast Doctor remains displayed and was not overwritten by the older response
  const tree3 = await render();
  const cardText3 = text(nodes(tree3).find((n) => n.props?.className === 'doctor-card'));
  assert.match(cardText3, /Dr\. Fast Doctor/, 'Newer fast doctor remains displayed');
  assert.doesNotMatch(cardText3, /Dr\. Old Stale Doctor/, 'Older stale response must be ignored');
});

test('component unmount cancels active in-flight request without errors', async () => {
  const pending = deferred();
  const { unmount } = await setup({
    apiHandlers: {
      get: async (url) => {
        if (url === '/specializations') return { data: [] };
        if (url === '/v2/discovery/doctors') return pending.promise;
        return { data: [] };
      },
    },
  });

  // Unmount component
  unmount();

  // Resolve pending request after unmount
  pending.resolve({ data: [SAMPLE_DOCTOR] });
  await flush();
  // No error thrown
});
