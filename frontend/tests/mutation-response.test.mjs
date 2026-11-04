import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import {
  mutationErrorMessage,
  requireAppointmentResult,
  requireAvailabilityResult,
  requirePatientProfileResult,
  requireSlotGenerationResult,
} from '../src/api/responseValidation.js';
import { createFormDrafts } from '../src/context/formDrafts.js';

const json = (data) => ({ status: 200, headers: { 'content-type': 'application/json' }, data });
const html = { status: 200, headers: { 'content-type': 'text/html' }, data: '<html>Sign In</html>' };
const availability = {
  id: 11, doctor: { id: 2 }, date: '2099-01-01', startTime: '09:00:00', endTime: '09:30:00',
};

function loadHandler(component, name, bindings) {
  // Exercise the dashboard callback itself without starting a browser or application server.
  const source = readFileSync(new URL(component, import.meta.url), 'utf8');
  const code = source.match(new RegExp(`const ${name} = (async [\\s\\S]+?\\n  \\});`))?.[1];
  assert.ok(code, `Missing ${name}`);
  return vm.runInNewContext(`(${code})`, bindings);
}

test('booking rejects HTML and preserves the form for a manual retry', async () => {
  const values = {
    selectedSpecialtyId: 'all',
    selectedDoctorId: '2',
    selectedDate: '2099-01-01',
    selectedSlotId: 3,
    bookingReason: 'Fixture reason',
  };
  const initial = { ...values };
  const formDrafts = createFormDrafts();
  formDrafts.selectOwner(7);
  let success = '';
  let error = '';
  let busy;
  const submit = loadHandler('../src/pages/PatientDashboard.jsx', 'handleBookSubmit', {
    ...values, user: { id: 7 }, patientProfile: { id: 1 }, profileNotFound: false, formDrafts,
    api: { post: async () => html },
    requireAppointmentResult, mutationErrorMessage,
    setBookingError: (message) => { error = message; },
    setBookingSuccess: (message) => { success = message; },
    setSelectedSlotId: (value) => { values.selectedSlotId = value; },
    setBookingReason: (value) => { values.bookingReason = value; },
    setIsBooking: (value) => { busy = value; },
    fetchAppointments: () => assert.fail('unexpected refresh'),
    fetchSlots: () => assert.fail('unexpected refresh'),
  });
  await submit({ preventDefault() {} });

  assert.equal(success, '');
  assert.match(error, /unexpected response/);
  assert.equal(busy, false);
  assert.deepEqual(values, initial);
  assert.deepEqual({ ...formDrafts.get(7, 'booking') }, initial);
});

test('HTML cannot mark slot generation complete or trigger a schedule refresh', async () => {
  let error;
  const generationLockRef = { current: false };
  const generate = loadHandler('../src/components/AvailabilityManager.jsx', 'handleGenerateSlots', {
    api: { post: async () => html },
    requireSlotGenerationResult, mutationErrorMessage,
    generationLockRef, generatedAvailabilityIds: new Set(),
    setIsGeneratingId: () => {},
    setGenerationError: (message) => { error = message; },
    setGenerationSuccess: (message) => assert.equal(message, ''),
    setGeneratedAvailabilityIds: () => assert.fail('marked generated'),
    setRefreshIndex: () => assert.fail('unexpected refresh'),
  });
  await generate(availability);

  assert.match(error, /unexpected response/);
  assert.equal(generationLockRef.current, false);
});

test('mutation validators reject HTML and incomplete or mismatched JSON', () => {
  const profile = {
    id: 8, user: { id: 7 }, firstName: 'Fixture', lastName: 'Patient',
    dateOfBirth: '2000-02-29', gender: 'OTHER', phone: '1234567890',
  };
  const slot = { ...availability, id: 12, status: 'AVAILABLE' };
  const request = { doctorId: 2, date: availability.date };
  const matchesAppointment = (data) => data.id === 9;
  for (const validate of [
    (response) => requireAppointmentResult(response, 'CONFIRMED', matchesAppointment),
    (response) => requirePatientProfileResult(response, 7),
    (response) => requireAvailabilityResult(response, request),
    (response) => requireSlotGenerationResult(response, availability),
  ]) {
    assert.throws(() => validate(html), /unexpected response/);
    assert.throws(() => validate(json({ id: '' })), /unexpected response/);
  }

  assert.throws(() => requireAppointmentResult(json({ id: 9, status: 'CANCELLED' }), 'CONFIRMED', matchesAppointment));
  assert.throws(() => requirePatientProfileResult(json(profile), 999));
  assert.throws(() => requireAvailabilityResult(json({ ...availability, startTime: '99:00' }), request));
  assert.throws(() => requireSlotGenerationResult(json([slot, null]), availability));
  assert.throws(() => requireAppointmentResult({ ...html, data: { id: 9, status: 'CONFIRMED' } },
    'CONFIRMED', matchesAppointment));

  assert.equal(requireAppointmentResult(json({ id: 9, status: 'CONFIRMED' }), 'CONFIRMED', matchesAppointment).id, 9);
  assert.equal(requirePatientProfileResult(json(profile), 7), profile);
  assert.equal(requireAvailabilityResult(json(availability), request), availability);
  assert.deepEqual(requireSlotGenerationResult(json([slot]), availability), [slot]);
  assert.deepEqual(requireSlotGenerationResult(json([]), availability), []);
});
