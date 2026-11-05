import api from './axios.js';
import { isApiId, requireJsonResponse, UnexpectedApiResponse } from './responseValidation.js';

const root = '/patient/assistant/sessions';
const isSession = (s) => isApiId(s?.id) && typeof s.title === 'string'
  && typeof s.createdAt === 'string' && typeof s.updatedAt === 'string';
const isMessage = (m) => isApiId(m?.id) && typeof m.requestId === 'string'
  && ['USER', 'ASSISTANT'].includes(m.role) && typeof m.content === 'string' && m.content.length > 0
  && typeof m.createdAt === 'string';

export function createAssistantService(client) {
  return {
    async sessions(page = 0, signal) {
      return requireJsonResponse(await client.get(root, { params: { page }, signal }),
        (data) => Array.isArray(data) && data.every(isSession));
    },
    async create() {
      return requireJsonResponse(await client.post(root, { title: 'New conversation' }), isSession);
    },
    async messages(id, before, signal) {
      return requireJsonResponse(await client.get(`${root}/${id}/messages`, { params: { before }, signal }),
        (data) => Array.isArray(data) && data.every(isMessage));
    },
    async send(id, request) {
      const payload = { requestId: request.requestId, content: request.content };
      const data = requireJsonResponse(await client.post(`${root}/${id}/messages`, payload, { timeout: 10000 }),
        (value) => Array.isArray(value?.messages) && value.messages.length === 2
          && value.messages.every(isMessage) && value.messages[0].role === 'USER'
          && value.messages[1].role === 'ASSISTANT'
          && value.messages.every((message) => message.requestId === request.requestId));
      return data.messages;
    },
    async remove(id) {
      const response = await client.delete(`${root}/${id}`);
      if (response.status !== 204) throw new UnexpectedApiResponse();
    },
  };
}

export function messageRequest(previous, content, uuid = () => crypto.randomUUID()) {
  const text = content.trim();
  return previous?.content === text ? previous : { requestId: uuid(), content: text };
}

export function mergeMessages(current, incoming) {
  return [...new Map([...current, ...incoming].map((message) => [message.id, message])).values()]
    .sort((a, b) => a.id - b.id);
}

export default createAssistantService(api);
