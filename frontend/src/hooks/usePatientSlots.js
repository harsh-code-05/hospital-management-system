import { useCallback, useEffect, useRef, useState } from 'react';
import api from '../api/axios';
import { isDate, requireAvailableSlots } from '../api/responseValidation';
import { RequestSequencer, executeSequencedRequest } from '../utils/requestSequencer';

function emptySelection(doctorId, date) {
  return {
    doctorId, date, status: doctorId && isDate(date) ? 'loading' : 'idle',
    slots: [], selectedSlotId: null,
  };
}

export default function usePatientSlots(draft, today) {
  const [initial] = useState(() => ({
    doctorId: String(draft?.selectedDoctorId ?? ''),
    date: draft?.selectedDate ?? today,
    slotId: draft?.selectedSlotId ?? null,
  }));
  const [selection, setSelection] = useState(() => emptySelection(initial.doctorId, initial.date));
  // Event handlers can run before React commits the next render.
  const current = useRef(selection);
  const [sequencer] = useState(() => new RequestSequencer());

  const commit = useCallback((next) => {
    current.current = next;
    setSelection(next);
  }, []);

  const requestSlots = useCallback((pending, slotToRestore = null) => {
    const { doctorId, date } = pending;
    return executeSequencedRequest(sequencer, async (signal) => {
      const response = await api.get('/appointment-slots/available', { params: { doctorId, date }, signal });
      return requireAvailableSlots(response, Number(doctorId), date);
    }, {
      onSuccess: (slots) => commit({
        ...pending, status: 'ready', slots,
        selectedSlotId: slots.some((slot) => slot.id === slotToRestore) ? slotToRestore : null,
      }),
      onError: () => commit({ ...pending, status: 'error' }),
    });
  }, [commit, sequencer]);

  useEffect(() => {
    sequencer.mount();
    const pending = emptySelection(initial.doctorId, initial.date);
    if (pending.status === 'loading') requestSlots(pending, initial.slotId);
    return () => sequencer.dispose();
  }, [initial, requestSlots, sequencer]);

  function loadSlots(doctorId, date) {
    if (!sequencer.isMounted) return;
    const pending = emptySelection(doctorId, date);
    commit(pending);
    if (pending.status === 'idle') {
      sequencer.cancelActive();
      return;
    }
    return requestSlots(pending);
  }

  const selectDoctor = (doctorId) => loadSlots(doctorId, current.current.date);
  const selectDate = (date) => loadSlots(current.current.doctorId, date);
  const refreshSlots = () => loadSlots(current.current.doctorId, current.current.date);

  function selectSlot(slotId) {
    if (!sequencer.isMounted) return;
    const latest = current.current;
    if (slotId === null || latest.slots.some((slot) => slot.id === slotId)) {
      commit({ ...latest, selectedSlotId: slotId });
    }
  }

  function getCurrentSlot(doctorId, date, slotId) {
    const latest = current.current;
    if (latest.status !== 'ready' || latest.doctorId !== doctorId || latest.date !== date
        || latest.selectedSlotId !== slotId) return undefined;
    return latest.slots.find((slot) => slot.id === slotId
      && slot.doctor.id === Number(doctorId) && slot.date === date);
  }

  return { selection, selectDoctor, selectDate, refreshSlots, selectSlot, getCurrentSlot };
}
