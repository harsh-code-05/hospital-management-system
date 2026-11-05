import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import { transformWithOxc } from 'vite';
import { mergeMessages, messageRequest } from '../src/api/aiAssistantService.js';
import { mutationErrorMessage } from '../src/api/responseValidation.js';

// The existing project uses Node tests with controlled hook renders, not a DOM test runner.
const source = readFileSync(new URL('../src/components/PatientAIAssistant.jsx', import.meta.url), 'utf8')
  .replace(/^import .*\r?\n/gm, '').replace('export default function', 'function');
const { code } = await transformWithOxc(source, 'PatientAIAssistant.jsx',
  { jsx: { runtime: 'classic', pragma: 'h', pragmaFrag: 'Fragment' } });
const flush = () => new Promise((resolve) => setImmediate(resolve));
const sessions = [1, 2].map((id) => ({ id, title: `Conversation ${id}`, updatedAt: '2026-11-05T12:00:00' }));
const messages = [{ id: 1, requestId: 'first', role: 'USER', content: 'Previous question', createdAt: sessions[0].updatedAt },
  { id: 2, requestId: 'first', role: 'ASSISTANT', content: 'Previous answer', createdAt: sessions[0].updatedAt }];
const submitEvent = { preventDefault() {} };

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

function nodes(tree) {
  if (Array.isArray(tree)) return tree.flatMap(nodes);
  return tree && typeof tree === 'object' ? [tree, ...nodes(tree.children)] : [];
}
function text(tree) {
  if (Array.isArray(tree)) return tree.map(text).join('');
  return tree && typeof tree === 'object' ? text(tree.children) : String(tree ?? '');
}
function sameDeps(a, b) { return a?.length === b.length && b.every((v, i) => Object.is(v, a[i])); }

async function setup(overrides = {}) {
  const reads = [];
  const sent = [];
  const removed = [];
  const hooks = [];
  let cursor = 0;
  let effects = [];
  const assistant = {
    sessions: async () => sessions,
    create: async () => ({ ...sessions[0], id: 3, title: 'New conversation' }),
    messages(id, before, signal) {
      const pending = deferred();
      reads.push({ id, before, signal, ...pending });
      return pending.promise;
    },
    send: async (id, request) => {
      sent.push({ id, request });
      return [{ ...messages[0], id: 3, requestId: request.requestId, content: request.content },
        { ...messages[1], id: 4, requestId: request.requestId, content: 'New reply' }];
    },
    remove: async (id) => { removed.push(id); },
    ...overrides,
  };
  const context = vm.createContext({
    h: (type, props, ...children) => ({ type, props: props ?? {}, children }), Fragment: Symbol(),
    useState(initial) {
      const cell = hooks[cursor++] ??= { value: initial };
      return [cell.value, (next) => { cell.value = typeof next === 'function' ? next(cell.value) : next; }];
    },
    useRef(initial) { return hooks[cursor++] ??= { current: initial }; },
    useCallback(fn, deps) {
      const i = cursor++;
      if (!sameDeps(hooks[i]?.deps, deps)) hooks[i] = { fn, deps };
      return hooks[i].fn;
    },
    useEffect(effect, deps) {
      const i = cursor++;
      if (!sameDeps(hooks[i]?.deps, deps)) effects.push(() => {
        hooks[i]?.cleanup?.();
        hooks[i] = { deps, cleanup: effect() };
      });
    },
    AbortController, window: { confirm: () => true },
    assistant, mergeMessages, messageRequest, mutationErrorMessage,
  });
  const Component = vm.runInContext(`${code}\nPatientAIAssistant`, context);
  function render() {
    cursor = 0;
    effects = [];
    const view = Component();
    effects.forEach((effect) => effect());
    return view;
  }
  function find(predicate) {
    const match = nodes(render()).find(predicate);
    assert.ok(match, 'Expected assistant control');
    return match;
  }
  const button = (label) => find((n) => n.type === 'button' && text(n).startsWith(label));
  const input = () => find((n) => n.type === 'textarea');
  const submit = () => find((n) => n.type === 'form').props.onSubmit(submitEvent);
  render();
  await flush();
  render();
  return { render, reads, sent, removed, button, input, submit,
    async settle(index = 0, data = messages) { reads[index].resolve(data); await flush(); render(); },
    change(value) { input().props.onChange({ target: { value } }); render(); },
    async unmount() { hooks.forEach((hook) => hook?.cleanup?.()); await flush(); },
  };
}

test('refresh reloads owned history, sends text and restores ready state', async () => {
  const view = await setup();
  assert.equal(view.input().props.disabled, true);
  await view.settle();
  assert.match(text(view.render()), /Previous question.*Previous answer/);
  assert.equal(view.input().props.disabled, false);
  view.change('New question');
  await view.submit();
  assert.equal(view.sent.length, 1);
  assert.deepEqual(Object.keys(view.sent[0].request).sort(), ['content', 'requestId']);
  assert.match(text(view.render()), /New reply/);
  assert.equal(view.input().props.value, '');
  await view.unmount();
});

test('failed send preserves draft and request ID and duplicate clicks are suppressed', async () => {
  let calls = [];
  let pending = deferred();
  const view = await setup({ send: (id, request) => { calls.push({ id, request }); return pending.promise; } });
  await view.settle();
  view.change('Keep this question');
  const first = view.submit();
  await view.submit();
  assert.equal(calls.length, 1);
  pending.reject(new Error('Server unavailable'));
  await first;
  assert.equal(view.input().props.value, 'Keep this question');
  assert.doesNotMatch(text(view.render()), /New reply/);
  pending = deferred();
  const retry = view.submit();
  assert.equal(calls[1].request.requestId, calls[0].request.requestId);
  pending.resolve([{ ...messages[0], id: 3 }, { ...messages[1], id: 4 }]);
  await retry;
  assert.equal(view.input().props.value, '');
  await view.unmount();
});

test('FAQ interface explains its scope and has no appointment-sharing control', async () => {
  const view = await setup();
  await view.settle();
  assert.match(text(view.render()), /predefined HMS FAQ/);
  assert.match(text(view.render()), /without looking up your appointments/);
  assert.doesNotMatch(text(view.render()), /Hugging Face|token|model|Share up to/);
  assert.equal(nodes(view.render()).some((node) => node.type === 'input' && node.props.type === 'checkbox'), false);
  await view.unmount();
});

test('unsupported-question guidance is displayed as a normal persisted reply', async () => {
  const fallback = 'I currently support basic hospital and application-related questions.';
  const view = await setup({ send: async (_id, request) => [
    { ...messages[0], id: 3, requestId: request.requestId, content: request.content },
    { ...messages[1], id: 4, requestId: request.requestId, content: fallback },
  ] });
  await view.settle();
  view.change('Explain interstellar navigation');
  await view.submit();
  assert.match(text(view.render()), /I currently support basic hospital and application-related questions/);
  assert.equal(view.input().props.value, '');
  await view.unmount();
});

test('switching conversations ignores a late history response and clears old draft', async () => {
  const view = await setup();
  view.button('Conversation 2').props.onClick();
  view.render();
  assert.equal(view.reads[0].signal.aborted, true);
  await view.settle(1, [{ ...messages[1], id: 10, content: 'Current conversation' }]);
  await view.settle(0, messages);
  assert.match(text(view.render()), /Current conversation/);
  assert.doesNotMatch(text(view.render()), /Previous answer/);
  assert.equal(view.input().props.value, '');
  await view.unmount();
});

test('history failure blocks sending and has a working retry for the same conversation', async () => {
  const view = await setup();
  view.reads[0].reject(new Error('History offline'));
  await flush();
  assert.equal(view.input().props.disabled, true);
  view.button('Retry loading conversation').props.onClick();
  view.render();
  assert.equal(view.reads.length, 2);
  await view.settle(1);
  assert.equal(view.input().props.disabled, false);
  assert.match(text(view.render()), /Previous answer/);
  await view.unmount();
});

test('new conversation and confirmed deletion update sidebar and empty state', async () => {
  const view = await setup();
  await view.settle();
  await view.button('New chat').props.onClick();
  view.render();
  assert.equal(view.reads[1].id, 3);
  await view.settle(1, []);
  assert.match(text(view.render()), /How can I help/);
  await view.button('Delete chat').props.onClick();
  assert.deepEqual(view.removed, [3]);
  assert.match(text(view.render()), /Start a conversation/);
  assert.doesNotMatch(text(view.render()), /New conversation/);
  await view.unmount();
});

test('Enter submits, Shift+Enter and IME composition do not submit', async () => {
  const view = await setup();
  await view.settle();
  let submitted = 0;
  let prevented = 0;
  const event = { key: 'Enter', shiftKey: false, nativeEvent: { isComposing: false },
    preventDefault() { prevented++; }, currentTarget: { form: { requestSubmit() { submitted++; } } } };
  view.input().props.onKeyDown(event);
  view.input().props.onKeyDown({ ...event, shiftKey: true });
  view.input().props.onKeyDown({ ...event, nativeEvent: { isComposing: true } });
  assert.equal(submitted, 1);
  assert.equal(prevented, 1);
  assert.equal(view.button('Send').props.disabled, true);
  await view.unmount();
});
