import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import { transformWithOxc } from 'vite';
import { createFormDrafts } from '../src/context/formDrafts.js';
import * as validation from '../src/api/responseValidation.js';
import { RequestSequencer, executeSequencedRequest } from '../src/utils/requestSequencer.js';

function componentSource(path) {
  return readFileSync(new URL(path, import.meta.url), 'utf8')
    .replace(/^import .*\r?\n/gm, '')
    .replace('export default function', 'function');
}

// Run the dashboard JSX and its hook with explicit renders and controlled API promises.
const { code: dashboard } = await transformWithOxc(componentSource('../src/pages/PatientDashboard.jsx'),
  'PatientDashboard.jsx', { jsx: { runtime: 'classic', pragma: 'h', pragmaFrag: 'Fragment' } });
const slotHook = componentSource('../src/hooks/usePatientSlots.js');

const A = '2099-06-10';
const B = '2099-06-11';
const SLOT_A = slot(31, A, 2, '09:00:00');
const SLOT_B = slot(41, B, 2, '14:00:00');
const OTHER_DOCTOR_SLOT = slot(51, A, 3, '16:00:00');
const profile = { id: 7, firstName: 'Test', lastName: 'Patient' };
const user = { id: 1, email: 'patient@test.invalid' };
const json = (data) => ({ data, headers: { 'content-type': 'application/json' } });
const flush = () => new Promise((resolve) => setImmediate(resolve));
const submitEvent = { preventDefault() {} };

function slot(id, date, doctorId, startTime) {
  return {
    id, date, doctor: { id: doctorId }, startTime,
    endTime: startTime.replace(':00:00', ':30:00'), status: 'AVAILABLE',
  };
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

function sameDeps(previous, next) {
  return previous?.length === next.length && next.every((value, i) => Object.is(value, previous[i]));
}

function nodes(tree) {
  if (Array.isArray(tree)) return tree.flatMap(nodes);
  if (!tree || typeof tree !== 'object') return [];
  return [tree, ...nodes(tree.children)];
}

function text(tree) {
  if (Array.isArray(tree)) return tree.map(text).join('');
  if (typeof tree === 'string' || typeof tree === 'number') return String(tree);
  return tree && typeof tree === 'object' ? text(tree.children) : '';
}

async function setup(draft = { bookingReason: 'Routine visit' }) {
  const formDrafts = createFormDrafts();
  formDrafts.selectOwner(user.id);
  formDrafts.save(user.id, 'booking', draft);
  const hooks = [];
  let cursor = 0;
  let stateWrites = 0;
  let pendingEffects = [];
  const reads = [];
  const posts = [];
  const bookingResponse = deferred();
  const appointmentsRefresh = deferred();
  let holdAppointmentsRefresh = false;

  const context = vm.createContext({
    h: (type, props, ...children) => ({ type, props: props ?? {}, children }),
    Fragment: Symbol('fragment'),
    useAuth: () => ({ user, formDrafts }),
    useNavigate: () => () => {},
    useState(initial) {
      const cell = hooks[cursor++] ??= { value: typeof initial === 'function' ? initial() : initial };
      return [cell.value, (next) => {
        stateWrites += 1;
        cell.value = typeof next === 'function' ? next(cell.value) : next;
      }];
    },
    useRef(initial) {
      return hooks[cursor++] ??= { current: initial };
    },
    useCallback(fn, deps) {
      const index = cursor++;
      if (!sameDeps(hooks[index]?.deps, deps)) hooks[index] = { fn, deps };
      return hooks[index].fn;
    },
    useEffect(effect, deps) {
      const index = cursor++;
      if (!sameDeps(hooks[index]?.deps, deps)) {
        pendingEffects.push(() => {
          hooks[index]?.cleanup?.();
          hooks[index] = { deps, cleanup: effect() };
        });
      }
    },
    api: {
      get(path, config) {
        if (path === '/appointment-slots/available') {
          const pending = deferred();
          reads.push({ ...pending, params: config.params, signal: config.signal });
          // Deliberately settle even after abort to exercise the latest-request check.
          return pending.promise;
        }
        if (path === '/patients/me') return Promise.resolve(json(profile));
        if (path === '/appointments' && holdAppointmentsRefresh) return appointmentsRefresh.promise;
        if (path === '/doctors') return Promise.resolve(json([{ id: 2 }, { id: 3 }]));
        assert.ok(path === '/appointments' || path === '/specializations', path);
        return Promise.resolve(json([]));
      },
      post(path, payload) {
        assert.equal(path, '/appointments');
        posts.push(payload);
        return bookingResponse.promise;
      },
    },
    ...validation,
    RequestSequencer,
    executeSequencedRequest,
  });

  const Dashboard = vm.runInContext(slotHook + '\n' + dashboard + '\nPatientDashboard', context);
  function render() {
    cursor = 0;
    pendingEffects = [];
    const view = Dashboard();
    pendingEffects.forEach((effect) => effect());
    return view;
  }
  function find(predicate) {
    const match = nodes(render()).find(predicate);
    assert.ok(match, 'Expected dashboard control');
    return match;
  }
  const byId = (id) => find((node) => node.props.id === id);
  const pick = (id, value) => byId(id).props.onChange({ target: { value } });
  const slots = () => nodes(render()).filter((node) => node.props.className?.startsWith('slot-pill'));
  const settle = async (index, slots) => {
    reads[index].resolve(json(slots));
    await flush();
  };
  const submit = () => find((node) => node.type === 'form').props.onSubmit;
  const confirm = () => find((node) => node.type === 'button' && node.props.type === 'submit');

  render();
  await flush();
  return {
    reads, posts, bookingResponse, appointmentsRefresh, byId, pick, slots, settle, submit, confirm,
    show: () => text(render()),
    times: () => slots().map(text),
    writes: () => stateWrites,
    unmount: () => hooks.forEach((cell) => cell?.cleanup?.()),
    startA: () => { pick('appointment-date', A); pick('doctor-select', '2'); },
    holdAppointmentsRefresh: () => { holdAppointmentsRefresh = true; },
  };
}

const chooseB = (dashboard) => dashboard.pick('appointment-date', B);
const confirmation = (selected = SLOT_A) => json({
  id: 91, status: 'CONFIRMED', patient: profile, doctor: selected.doctor, slot: selected,
});

test('only date B slots render when B resolves before date A', async () => {
  const dashboard = await setup();
  dashboard.startA();
  chooseB(dashboard);
  assert.deepEqual(dashboard.reads.map((request) => request.params.date), [A, B]);
  assert.equal(dashboard.reads[0].signal.aborted, true);
  await dashboard.settle(1, [SLOT_B]);
  await dashboard.settle(0, [SLOT_A]);
  assert.equal(dashboard.byId('appointment-date').props.value, B);
  assert.deepEqual(dashboard.times(), ['2:00 PM – 2:30 PM']);
});

test('a late failure preserves the newer results and selection', async () => {
  const dashboard = await setup();
  dashboard.startA();
  chooseB(dashboard);
  await dashboard.settle(1, [SLOT_B]);
  dashboard.slots()[0].props.onClick();
  dashboard.reads[0].reject(new Error('Old request failed'));
  await flush();
  assert.match(dashboard.slots()[0].props.className, /active/);
  assert.equal(dashboard.confirm().props.disabled, false);
  assert.doesNotMatch(dashboard.show(), /Unable to load available slots/);
});

test('an older response cannot stop loading while the latest request is pending', async () => {
  const dashboard = await setup();
  dashboard.startA();
  chooseB(dashboard);
  await dashboard.settle(0, [SLOT_A]);
  assert.match(dashboard.show(), /Loading available slots/);
  assert.doesNotMatch(dashboard.show(), /No available slots/);
  assert.deepEqual(dashboard.times(), []);
  await dashboard.settle(1, [SLOT_B]);
  assert.deepEqual(dashboard.times(), ['2:00 PM – 2:30 PM']);
});

test('changing doctors invalidates the pending request for the same date', async () => {
  const dashboard = await setup();
  dashboard.startA();
  dashboard.pick('doctor-select', '3');
  assert.equal(dashboard.reads[0].signal.aborted, true);
  assert.equal(dashboard.reads[1].params.doctorId, '3');
  assert.equal(dashboard.reads[1].params.date, A);
  await dashboard.settle(1, [OTHER_DOCTOR_SLOT]);
  await dashboard.settle(0, [SLOT_A]);
  assert.deepEqual(dashboard.times(), ['4:00 PM – 4:30 PM']);
});

for (const field of ['appointment-date', 'doctor-select']) {
  test('clearing ' + field + ' cancels the pending request without an unfiltered GET', async () => {
    const dashboard = await setup();
    dashboard.startA();
    dashboard.pick(field, '');
    assert.equal(dashboard.reads.length, 1);
    assert.equal(dashboard.reads[0].signal.aborted, true);
    await dashboard.settle(0, [SLOT_A]);
    assert.deepEqual(dashboard.times(), []);
    assert.doesNotMatch(dashboard.show(), /Loading available slots|No available slots/);
    assert.equal(dashboard.confirm().props.disabled, true);
  });
}

test('unmount aborts a pending request and ignores its late response', async () => {
  const dashboard = await setup();
  dashboard.startA();
  dashboard.unmount();
  const writes = dashboard.writes();
  assert.equal(dashboard.reads[0].signal.aborted, true);
  await dashboard.settle(0, [SLOT_A]);
  assert.equal(dashboard.writes(), writes);
});

test('changing the date clears old slots and blocks an old submit before React renders', async () => {
  const dashboard = await setup();
  dashboard.startA();
  await dashboard.settle(0, [SLOT_A]);
  dashboard.slots()[0].props.onClick();
  assert.equal(dashboard.confirm().props.disabled, false);
  const oldSubmit = dashboard.submit();
  const changeDate = dashboard.byId('appointment-date').props.onChange;
  changeDate({ target: { value: B } });
  await oldSubmit(submitEvent);
  assert.deepEqual(dashboard.posts, []);
  assert.deepEqual(dashboard.times(), []);
  assert.equal(dashboard.confirm().props.disabled, true);
  await dashboard.settle(1, [SLOT_B]);
  assert.doesNotMatch(dashboard.slots()[0].props.className, /active/);
  assert.equal(dashboard.confirm().props.disabled, true);
});

test('draft restoration cannot put back a stale date or selected slot', async () => {
  const dashboard = await setup({ selectedDoctorId: '2', selectedDate: A, selectedSlotId: SLOT_A.id });
  assert.equal(dashboard.reads[0].params.date, A);
  chooseB(dashboard);
  await dashboard.settle(1, [SLOT_B]);
  await dashboard.settle(0, [SLOT_A]);
  assert.equal(dashboard.byId('appointment-date').props.value, B);
  assert.deepEqual(dashboard.times(), ['2:00 PM – 2:30 PM']);
  assert.doesNotMatch(dashboard.slots()[0].props.className, /active/);
});

test('a current slot books with the selected date and refreshes availability', async () => {
  const dashboard = await setup();
  dashboard.startA();
  await dashboard.settle(0, [SLOT_A]);
  dashboard.slots()[0].props.onClick();
  const booking = dashboard.submit()(submitEvent);
  const [{ patientId, doctorId, slotId, appointmentDate, reason }] = dashboard.posts;
  assert.deepEqual([patientId, doctorId, slotId, appointmentDate, reason], [7, 2, 31, A, 'Routine visit']);
  dashboard.bookingResponse.resolve(confirmation());
  await booking;
  assert.match(dashboard.show(), /Appointment #91 confirmed successfully/);
  assert.equal(dashboard.reads[1].params.date, A);
  assert.equal(dashboard.byId('appointment-reason').props.value, '');
  await dashboard.settle(1, []);
  assert.match(dashboard.show(), /No available slots for this doctor/);
});

test('a date change supersedes a post-booking refresh already in flight', async () => {
  const dashboard = await setup();
  dashboard.startA();
  await dashboard.settle(0, [SLOT_A]);
  dashboard.slots()[0].props.onClick();
  const booking = dashboard.submit()(submitEvent);
  dashboard.bookingResponse.resolve(confirmation());
  await booking;
  chooseB(dashboard);
  await dashboard.settle(2, [SLOT_B]);
  await dashboard.settle(1, [SLOT_A]);
  assert.deepEqual(dashboard.times(), ['2:00 PM – 2:30 PM']);
});

test('a delayed post-booking handler refreshes the current selection rather than its captured date', async () => {
  const dashboard = await setup();
  dashboard.startA();
  await dashboard.settle(0, [SLOT_A]);
  dashboard.slots()[0].props.onClick();
  const changeDate = dashboard.byId('appointment-date').props.onChange;
  dashboard.holdAppointmentsRefresh();
  const booking = dashboard.submit()(submitEvent);
  dashboard.bookingResponse.resolve(confirmation());
  await flush();
  changeDate({ target: { value: B } });
  dashboard.appointmentsRefresh.resolve(json([]));
  await booking;
  assert.deepEqual(dashboard.reads.map((request) => request.params.date), [A, B, B]);
  await dashboard.settle(2, [SLOT_B]);
  assert.deepEqual(dashboard.times(), ['2:00 PM – 2:30 PM']);
});

test('a successful empty response displays the selected date without an error', async () => {
  const dashboard = await setup();
  dashboard.startA();
  await dashboard.settle(0, []);
  assert.match(dashboard.show(), /No available slots for this doctor on .*2099/);
  assert.doesNotMatch(dashboard.show(), /Unable to load available slots/);
});

test('the latest request failure shows an error instead of an empty result', async () => {
  const dashboard = await setup();
  dashboard.startA();
  dashboard.reads[0].reject(new Error('Failed to fetch'));
  await flush();
  assert.match(dashboard.show(), /Unable to load available slots/);
  assert.doesNotMatch(dashboard.show(), /No available slots|Loading available slots/);
  assert.equal(dashboard.confirm().props.disabled, true);
});

test('slots for a different date in the latest response cannot be selected or booked', async () => {
  const dashboard = await setup();
  dashboard.startA();
  await dashboard.settle(0, [SLOT_B]);
  assert.deepEqual(dashboard.times(), []);
  await dashboard.submit()(submitEvent);
  assert.deepEqual(dashboard.posts, []);
});

test('a synchronous second submit cannot issue a duplicate booking POST', async () => {
  const dashboard = await setup();
  dashboard.startA();
  await dashboard.settle(0, [SLOT_A]);
  dashboard.slots()[0].props.onClick();
  const submit = dashboard.submit();
  const booking = submit(submitEvent);
  await submit(submitEvent);
  assert.equal(dashboard.posts.length, 1);
  dashboard.bookingResponse.resolve(confirmation());
  await booking;
});
