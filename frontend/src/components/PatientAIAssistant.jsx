import { useCallback, useEffect, useRef, useState } from 'react';
import assistant, { mergeMessages, messageRequest } from '../api/aiAssistantService.js';
import { mutationErrorMessage } from '../api/responseValidation.js';
import './PatientAIAssistant.css';

function timestamp(value) {
  const date = new Date(value);
  return Number.isNaN(date.valueOf()) ? '' : date.toLocaleString();
}

export default function PatientAIAssistant() {
  const [sessions, setSessions] = useState([]);
  const [selected, setSelected] = useState(null);
  const [messages, setMessages] = useState([]);
  const [draft, setDraft] = useState('');
  const [loadingSessions, setLoadingSessions] = useState(true);
  const [loadingMessages, setLoadingMessages] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [listError, setListError] = useState('');
  const [hasEarlier, setHasEarlier] = useState(false);
  const [page, setPage] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [reload, setReload] = useState(0);
  const [historyFailed, setHistoryFailed] = useState(false);
  const operation = useRef(false);
  const pending = useRef(null);
  const active = useRef(null);
  const generation = useRef(0);
  const mounted = useRef(true);
  const bottom = useRef(null);

  const selectConversation = useCallback((id) => {
    active.current = id;
    generation.current++;
    pending.current = null;
    setSelected(id);
    setMessages([]);
    setDraft('');
    setError('');
    setHasEarlier(false);
    setHistoryFailed(false);
    setLoadingMessages(id !== null);
    setReload((value) => value + 1);
  }, []);

  useEffect(() => {
    mounted.current = true;
    const controller = new AbortController();
    assistant.sessions(0, controller.signal).then((data) => {
      if (!controller.signal.aborted) {
        setSessions(data);
        selectConversation(data[0]?.id ?? null);
        setHasMore(data.length === 30);
      }
    }).catch((cause) => {
      if (!controller.signal.aborted) {
        setListError(mutationErrorMessage(cause, 'Unable to load conversations. Refresh to retry.'));
      }
    }).finally(() => { if (!controller.signal.aborted) setLoadingSessions(false); });
    return () => { mounted.current = false; controller.abort(); };
  }, [selectConversation]);

  useEffect(() => {
    const controller = new AbortController();
    const request = generation.current;
    if (selected !== null) {
      assistant.messages(selected, undefined, controller.signal).then((data) => {
        if (!controller.signal.aborted && generation.current === request) {
          setMessages(data);
          setHasEarlier(data.length === 50);
        }
      }).catch((cause) => {
        if (!controller.signal.aborted && generation.current === request) {
          setHistoryFailed(true);
          setError(mutationErrorMessage(cause, 'Unable to load this conversation. Retry loading before sending.'));
        }
      }).finally(() => {
        if (!controller.signal.aborted && generation.current === request) setLoadingMessages(false);
      });
    }
    return () => { controller.abort(); };
  }, [selected, reload]);

  useEffect(() => {
    bottom.current?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
  }, [messages, busy]);

  async function newConversation() {
    if (operation.current) return;
    operation.current = true;
    setBusy(true);
    setListError('');
    try {
      const created = await assistant.create();
      if (mounted.current) {
        setSessions((current) => [created, ...current]);
        selectConversation(created.id);
      }
    } catch (cause) {
      if (mounted.current) setListError(mutationErrorMessage(cause, 'Unable to create a conversation.'));
    } finally {
      operation.current = false;
      if (mounted.current) setBusy(false);
    }
  }

  async function deleteConversation() {
    if (selected === null || operation.current || !window.confirm('Delete this conversation and its messages?')) return;
    operation.current = true;
    setBusy(true);
    setError('');
    const id = selected;
    try {
      await assistant.remove(id);
      if (mounted.current) {
        setSessions((current) => current.filter((session) => session.id !== id));
        selectConversation(null);
      }
    } catch (cause) {
      if (mounted.current) setError(mutationErrorMessage(cause, 'Unable to delete the conversation.'));
    } finally {
      operation.current = false;
      if (mounted.current) setBusy(false);
    }
  }

  async function send(event) {
    event.preventDefault();
    if (!draft.trim() || draft.length > 2000 || selected === null || operation.current || loadingMessages || historyFailed) return;
    operation.current = true;
    setBusy(true);
    setError('');
    const id = selected;
    try {
      const request = messageRequest(pending.current, draft);
      pending.current = request;
      const turn = await assistant.send(id, request);
      if (mounted.current && active.current === id) {
        setMessages((current) => mergeMessages(current, turn));
        setDraft('');
        pending.current = null;
        setSessions((current) => {
          const session = current.find((item) => item.id === id);
          return session ? [{ ...session, updatedAt: turn[1].createdAt }, ...current.filter((item) => item.id !== id)] : current;
        });
      }
    } catch (cause) {
      if (mounted.current && active.current === id) {
        setError(mutationErrorMessage(cause, 'Unable to confirm delivery. Retry the same message to check its result.'));
      }
    } finally {
      operation.current = false;
      if (mounted.current) setBusy(false);
    }
  }

  async function loadEarlier() {
    if (operation.current || loadingMessages || !messages.length) return;
    operation.current = true;
    setLoadingMessages(true);
    setError('');
    const id = selected;
    const request = generation.current;
    try {
      const older = await assistant.messages(id, messages[0].id);
      if (mounted.current && request === generation.current) {
        setMessages((current) => mergeMessages(older, current));
        setHasEarlier(older.length === 50);
      }
    } catch (cause) {
      if (mounted.current && request === generation.current) setError(mutationErrorMessage(cause, 'Unable to load earlier messages.'));
    } finally {
      operation.current = false;
      if (mounted.current && request === generation.current) setLoadingMessages(false);
    }
  }

  async function loadMoreSessions() {
    if (operation.current) return;
    operation.current = true;
    setLoadingSessions(true);
    setListError('');
    try {
      const next = await assistant.sessions(page + 1);
      if (mounted.current) {
        setSessions((current) => [...new Map([...current, ...next].map((session) => [session.id, session])).values()]);
        setPage((current) => current + 1);
        setHasMore(next.length === 30);
      }
    } catch (cause) {
      if (mounted.current) setListError(mutationErrorMessage(cause, 'Unable to load more conversations.'));
    } finally {
      operation.current = false;
      if (mounted.current) setLoadingSessions(false);
    }
  }

  return (
    <section className="patient-assistant" aria-label="Patient AI Assistant">
      <aside className="assistant-sidebar" aria-label="Conversations">
        <button type="button" className="assistant-primary" disabled={busy || loadingSessions} onClick={newConversation}>New chat</button>
        {listError && <p role="alert" className="assistant-error">{listError}</p>}
        {loadingSessions && <p role="status">Loading conversations…</p>}
        {!loadingSessions && !listError && sessions.length === 0 && <p>No conversations yet.</p>}
        <ul>
          {sessions.map((session) => (
            <li key={session.id}>
              <button type="button" disabled={busy} aria-current={selected === session.id ? 'true' : undefined}
                onClick={() => { selectConversation(session.id); }}>
                <strong>{session.title}</strong>
                <time dateTime={session.updatedAt}>{timestamp(session.updatedAt)}</time>
              </button>
            </li>
          ))}
        </ul>
        {hasMore && <button type="button" disabled={busy || loadingSessions} onClick={loadMoreSessions}>Load more conversations</button>}
      </aside>
      <div className="assistant-conversation">
        <header>
          <div><h2>Patient AI Assistant</h2><p>HMS FAQ help for login, doctors and appointments.</p></div>
          {selected !== null && <button type="button" disabled={busy} onClick={deleteConversation}>Delete chat</button>}
        </header>
        <p className="assistant-safety">Answers come from a predefined HMS FAQ, without looking up your appointments. This chat cannot change bookings or provide medical advice. For emergencies, contact local emergency services.</p>
        <div className="assistant-messages" aria-label="Messages" aria-busy={loadingMessages || busy}>
          {hasEarlier && <button type="button" disabled={loadingMessages || busy} onClick={loadEarlier}>Load earlier messages</button>}
          {loadingMessages && <p role="status">Loading messages…</p>}
          {!loadingMessages && !historyFailed && messages.length === 0 && <div className="assistant-empty">
            <h3>{selected === null ? 'Start a conversation' : 'How can I help?'}</h3>
            <p>{selected === null ? 'Choose New chat to begin.' : 'Ask how to sign in, find doctors, book or cancel an appointment, or use the Patient Portal.'}</p>
          </div>}
          {messages.map((message) => (
            <article key={message.id} className={`assistant-message assistant-message-${message.role.toLowerCase()}`}>
              <strong>{message.role === 'USER' ? 'You' : 'Assistant'}</strong>
              <p>{message.content}</p>
              <time dateTime={message.createdAt}>{timestamp(message.createdAt)}</time>
            </article>
          ))}
          {busy && <p role="status" className="assistant-typing">Working on your request…</p>}
          <div ref={bottom} />
        </div>
        {error && <p role="alert" className="assistant-error">{error}</p>}
        {historyFailed && <button type="button" disabled={busy || loadingMessages}
          onClick={() => { selectConversation(selected); }}>Retry loading conversation</button>}
        <form onSubmit={send} className="assistant-composer">
          <label htmlFor="assistant-message">Your message</label>
          <textarea id="assistant-message" value={draft} maxLength={2000}
            disabled={busy || selected === null || loadingMessages || historyFailed} placeholder="Ask how to use the Patient Portal"
            onChange={(event) => { setDraft(event.target.value); }}
            onKeyDown={(event) => {
              if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
                event.preventDefault();
                event.currentTarget.form.requestSubmit();
              }
            }} />
          <div className="assistant-composer-actions">
            <small>Up to 2,000 characters. Avoid personal details and medical records. Enter sends · Shift + Enter adds a line.</small>
            <button className="assistant-primary" type="submit" disabled={busy || loadingMessages || historyFailed || selected === null || !draft.trim()}>Send</button>
          </div>
        </form>
      </div>
    </section>
  );
}
