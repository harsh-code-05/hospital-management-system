export class UnexpectedApiResponse extends Error {
  constructor() {
    super('The server returned an unexpected response. Refresh to check whether the action took effect.');
  }
}

export const isApiId = (value) => Number.isSafeInteger(value) && value > 0;
const isText = (value) => typeof value === 'string' && value.trim().length > 0;
const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
export function isDate(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const timestamp = Date.parse(`${value}T00:00:00Z`);
  return Number.isFinite(timestamp) && new Date(timestamp).toISOString().slice(0, 10) === value;
}
const isTime = (value) => typeof value === 'string'
  && /^(?:[01]\d|2[0-3]):[0-5]\d(?::[0-5]\d(?:\.\d+)?)?$/.test(value);

export function requireJsonResponse(response, matches) {
  const contentType = response?.headers?.get?.('content-type') ?? response?.headers?.['content-type'];
  const mediaType = typeof contentType === 'string' ? contentType.split(';', 1)[0].trim().toLowerCase() : '';
  if (!mediaType || (mediaType !== 'application/json' && !mediaType.endsWith('+json'))
      || !matches(response.data)) {
    throw new UnexpectedApiResponse();
  }
  return response.data;
}

export function requireAppointmentResult(response, status, matches) {
  return requireJsonResponse(response, (data) =>
    isObject(data) && isApiId(data.id) && data.status === status && matches(data),
  );
}

export function requirePatientProfileResult(response, userId) {
  return requireJsonResponse(response, (data) =>
    isObject(data) && isApiId(data.id) && data.user?.id === userId
    && isText(data.firstName) && isText(data.lastName)
    && isDate(data.dateOfBirth) && isText(data.gender) && isText(data.phone),
  );
}

function isScheduleInterval(data) {
  return isObject(data) && isApiId(data.id) && isApiId(data.doctor?.id)
    && isDate(data.date) && isTime(data.startTime) && isTime(data.endTime);
}

export function requireAvailabilityResult(response, request) {
  return requireJsonResponse(response, (data) => isScheduleInterval(data)
    && data.doctor.id === request.doctorId && data.date === request.date);
}

export function requireAvailableSlots(response, doctorId, date) {
  return requireJsonResponse(response, (data) => Array.isArray(data)
    && data.every((slot) => isScheduleInterval(slot) && slot.status === 'AVAILABLE'
      && slot.doctor.id === doctorId && slot.date === date));
}

export function requireSlotGenerationResult(response, availability) {
  return requireAvailableSlots(response, availability.doctor.id, availability.date);
}

export function mutationErrorMessage(error, fallback) {
  if (error instanceof UnexpectedApiResponse) return error.message;
  return error.response?.data?.message || fallback;
}
