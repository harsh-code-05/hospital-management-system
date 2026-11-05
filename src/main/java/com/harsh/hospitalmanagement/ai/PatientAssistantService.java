package com.harsh.hospitalmanagement.ai;

import com.harsh.hospitalmanagement.entity.Patient;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.exception.ConflictException;
import com.harsh.hospitalmanagement.exception.ForbiddenException;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.security.LocalAccount;
import com.harsh.hospitalmanagement.service.PatientService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class PatientAssistantService {
    private final PatientService patients;
    private final AiChatSessionRepository sessions;
    private final AiChatMessageRepository messages;
    private final FaqChatbot chatbot;
    private final AssistantRequestLimiter limiter;

    public PatientAssistantService(PatientService patients, AiChatSessionRepository sessions,
                                   AiChatMessageRepository messages, FaqChatbot chatbot,
                                   AssistantRequestLimiter limiter) {
        this.patients = patients;
        this.sessions = sessions;
        this.messages = messages;
        this.chatbot = chatbot;
        this.limiter = limiter;
    }

    public AssistantDtos.Session create(LocalAccount actor, AssistantDtos.CreateSession request) {
        Patient patient = patient(actor);
        String title = request.title() == null || request.title().isBlank() ? "New conversation" : request.title().strip();
        if (title.length() > 80) {
            throw new BadRequestException("Conversation titles must not exceed 80 characters");
        }
        return AssistantDtos.Session.of(sessions.save(new AiChatSession(patient, title)));
    }

    @Transactional(readOnly = true)
    public List<AssistantDtos.Session> list(LocalAccount actor, int page) {
        Patient patient = patient(actor);
        return sessions.findByPatient_IdOrderByUpdatedAtDescIdDesc(patient.getId(), PageRequest.of(page, 30))
                .stream().map(AssistantDtos.Session::of).toList();
    }

    @Transactional(readOnly = true)
    public List<AssistantDtos.Message> history(LocalAccount actor, Long sessionId, Long before) {
        owned(actor, sessionId, false);
        var recent = new ArrayList<>(messages.findRecent(sessionId, before, PageRequest.of(0, 50)));
        Collections.reverse(recent);
        return recent.stream().map(AssistantDtos.Message::of).toList();
    }

    public AssistantDtos.Turn send(LocalAccount actor, Long sessionId, AssistantDtos.SendMessage request) {
        if (request.content() == null || request.content().isBlank() || request.content().length() > 2000
                || request.requestId() == null) {
            throw new BadRequestException("A message of 1 to 2000 characters and a request ID are required");
        }
        AiChatSession session = owned(actor, sessionId, true);
        var saved = messages.findBySession_IdAndRequestIdOrderByIdAsc(sessionId, request.requestId());
        if (!saved.isEmpty()) {
            AiChatMessage original = saved.getFirst();
            if (saved.size() != 2 || original.getRole() != AiChatMessage.Role.USER
                    || saved.getLast().getRole() != AiChatMessage.Role.ASSISTANT) {
                throw new ConflictException("The saved message is incomplete. Please contact hospital support");
            }
            if (!original.getContent().equals(request.content().strip())) {
                throw new ConflictException("A request ID cannot be reused for a different message");
            }
            return new AssistantDtos.Turn(saved.stream().map(AssistantDtos.Message::of).toList());
        }
        limiter.acquire(actor.id());
        String content = request.content().strip();
        String answer = chatbot.answer(content);
        var user = messages.save(new AiChatMessage(session, AiChatMessage.Role.USER, content,
                request.requestId(), false));
        var assistant = messages.save(new AiChatMessage(session, AiChatMessage.Role.ASSISTANT, answer,
                request.requestId(), false));
        session.touch();
        return new AssistantDtos.Turn(List.of(AssistantDtos.Message.of(user), AssistantDtos.Message.of(assistant)));
    }

    public void delete(LocalAccount actor, Long sessionId) {
        AiChatSession session = owned(actor, sessionId, true);
        messages.deleteForSession(sessionId);
        sessions.delete(session);
    }

    private Patient patient(LocalAccount actor) {
        Patient patient = patients.getPatientByUserId(actor.id());
        if (patient.getUser().getRole() != Role.PATIENT) {
            throw new ForbiddenException("The assistant is available to patients only");
        }
        return patient;
    }

    private AiChatSession owned(LocalAccount actor, Long id, boolean lock) {
        Long patientId = patient(actor).getId();
        return (lock ? sessions.findOwnedForUpdate(id, patientId) : sessions.findByIdAndPatient_Id(id, patientId))
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found"));
    }
}
