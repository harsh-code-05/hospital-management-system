package com.harsh.hospitalmanagement.ai;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Local, predefined help. No record lookups, conversation context, tools, or network clients. */
@Component
public class FaqChatbot {
    static final String FALLBACK = "I currently support basic hospital and HMS application questions from a limited FAQ knowledge base. "
            + "Try asking about signing in, your patient profile, finding doctors, booking or cancelling appointments. "
            + "I cannot look up personal records or provide medical advice.";
    private static final Pattern EMERGENCY = Pattern.compile(
            "\\b(emergenc\\w*|chest pain|can t breathe|cant breathe|cannot breathe|severe bleeding|unconscious|heart attack|suicid\\w*)\\b|\\bkill myself\\b");
    private static final Pattern MEDICAL = Pattern.compile(
            "\\b(diagnos\\w*|prescrib\\w*|dosages?|doses?|symptoms?|medications?|medicines?|treat\\w*|cure\\w*|diseases?|pain|fever|headache|tablets?|antibiotics?)\\b");
    private static final String APPOINTMENT = "\\b(appointments?|bookings?|consultations?|visits?|slots?)\\b";

    // Answers and their source references are documented in docs/patient-ai-assistant.md.
    // Order puts safety, unsupported actions, and specific intents before broad navigation.
    private static final List<Faq> KNOWLEDGE_BASE = List.of(
            faq("\\b(forgot|forgotten|reset|recover|recovery|lost|change)\\b.*\\b(password|login|account)\\b|\\bpassword\\b.*\\b(reset|recover|forgot)\\b",
                    "This version has no self-service password-reset screen. I cannot reset passwords or recover accounts. "
                            + "Use your account's original sign-in method and ask your HMS administrator for help if needed. Never share a password in chat."),
            faq("\\b(reschedul\\w*|rebook\\w*)\\b|(?=.*" + APPOINTMENT + ")(?=.*\\b(change|move)\\b)",
                    "There is no direct Reschedule action in this version. Cancellation and a new booking are separate manual actions in the portal. "
                            + "A replacement slot is not reserved or guaranteed, and I cannot change a booking."),
            faq("\\b(cancellation|cancellations)\\b|(?=.*" + APPOINTMENT + ")(?=.*\\b(cancel|cancelling|canceling|delete|remove)\\b)",
                    "Open My Appointments, find your CONFIRMED appointment, choose Cancel Appointment, then confirm with Yes, Cancel Appointment. "
                            + "Cancellation is only available for confirmed appointments. I can explain the steps but cannot cancel anything for you. "
                            + "Cancellation deadlines and refund policies are not in this FAQ; confirm them directly with the clinic."),
            faq("\\b(no|not|none|empty|unavailable)\\b.*\\b(slots?|availability|doctors?)\\b|\\b(slots?|availability)\\b.*\\b(empty|unavailable|missing)\\b",
                    "If Book Appointment shows no available slots, try another date or doctor. If loading fails, retry or refresh the page. "
                            + "Only slots currently displayed by the portal can be selected; this chatbot does not check or promise availability."),
            faq("\\bavailability\\b|(?=.*\\b(slots?|doctors?)\\b)(?=.*\\b(available|free|today|tomorrow)\\b)",
                    "To check availability, open Book Appointment, select a doctor and choose a date. The portal displays the current available time slots. "
                            + "This FAQ chatbot does not check live availability or guarantee a booking."),
            faq("(?=.*(" + APPOINTMENT + "|\\b(speciali[sz]ations?|doctors?)\\b))(?=.*\\b(book|reserve|schedule|make|create|arrange)\\b)"
                            + "|\\b(how|help|instructions|steps)\\b.*\\bbooking\\b|\\bbooking (help|instructions|steps)\\b|^(appointment )?booking$|\\bbook$",
                    "Complete My Profile, then open Book Appointment. Optionally filter by specialization, select a doctor, choose a date and an available time slot, "
                            + "enter Reason for Visit, then choose Confirm Appointment. Check My Appointments for the saved booking. "
                            + "I provide instructions only and cannot reserve a slot."),
            faq("(?=.*\\b(my|me|i)\\b)(?=.*\\b(appointments?|bookings?|doctor|records?|results?|diagnos\\w*)\\b)(?=.*\\b(show|list|when|who|what|is|are|view|see|check|next|upcoming)\\b)",
                    "Open My Appointments to see your actual doctor, date, time and appointment status. Use its status filters or Refresh to update the list. "
                            + "This FAQ chatbot does not read personal appointment or medical records."),
            faq("(?=.*\\b(profile|personal details)\\b)(?=.*\\b(edit|change|update|correct)\\b)",
                    "My Profile displays your saved patient details. This version does not provide a saved-profile edit form, and this chatbot cannot change personal details. "
                            + "Ask your HMS administrator for help with corrections."),
            faq("\\b(regist\\w*|sign up|signup)\\b|\\b(create|new|open)\\b.*\\b(account|patient account)\\b",
                    "The current website has no separate Sign Up form. For a new Google-based patient account, use Sign in with Google on the Sign In page. "
                            + "If you already have a password account, keep using that sign-in method; accounts are not automatically linked by email. "
                            + "After signing in, complete My Profile before booking."),
            faq("\\b(google|oauth)\\b",
                    "On the Sign In page, choose Sign in with Google and complete Google's sign-in flow. "
                            + "An existing password account is not automatically linked to Google just because the email matches. "
                            + "If Google sign-in fails for an existing account, use its original sign-in method."),
            faq("\\b(log out|logout|sign out|signout)\\b",
                    "Use Sign Out in the Patient Portal header to end your session. Sign in again when you want to return. Never share your password in chat."),
            faq("\\b(log in|log into|login|sign in|sign into|signin|password|session expired|signed out)\\b",
                    "Open Sign In, enter your registered email and password, and submit, or choose Sign in with Google for a Google-based account. "
                            + "If your session expires, sign in again and check the portal before retrying an action. Never send login credentials to this chatbot."),
            faq("\\b(profile|personal details|date of birth)\\b",
                    "Open My Profile. If your profile is incomplete, enter your first and last name, past date of birth, gender and phone, then submit the profile form. "
                            + "A completed patient profile is required for appointment booking. Enter these details in the form, not in chat."),
            faq("\\b(speciali[sz]ations?|specialties|speciality|specialty|departments?)\\b|(?=.*\\b(doctors?|specialists?)\\b)(?=.*\\b(find|search|discover\\w*|choose|select|browse|available)\\b)",
                    "Open Book Appointment and optionally use Filter by Specialization, then Select Doctor. "
                            + "A specialization is a doctor's area of practice. Doctor options show names and available specialization/qualification details. "
                            + "Use the portal's current list; this FAQ does not list available doctors or recommend a specialist for symptoms."),
            faq("\\b(confirmed|cancelled|canceled|completed|no show|no_show|status|statuses)\\b",
                    "CONFIRMED means the booking is confirmed; CANCELLED means it was cancelled; COMPLETED means it was marked complete; NO_SHOW labels a missed appointment. "
                            + "Check My Appointments for your actual status. The chatbot cannot change it."),
            faq("\\b(hours?|opening|visiting|address|location|insurance|parking|refunds?|payments?|fees?|prices?|costs?|charges?|polic\\w*|contact|phone|telephone)\\b",
                    "Hospital-specific opening hours, addresses, contact numbers, visiting rules, fees, insurance and refund policies are not in this FAQ knowledge base. "
                            + "Please confirm those details directly with the hospital or clinic. I will not guess them."),
            faq("\\b(what is|what s|meaning of|define)\\b.*\\b(appointment|consultation)\\b",
                    "An appointment is a scheduled consultation with a doctor. Use Book Appointment for booking instructions and My Appointments to view your saved bookings."),
            faq("\\b(hello|hi|hey|help|hms|hospital management system|portal|chatbot|assistant|faq|thank you|thanks)\\b",
                    "I am the HMS FAQ assistant. Use My Appointments to view bookings, Book Appointment to find a doctor and book a slot, and My Profile to complete your patient details. "
                            + "Ask me for instructions about registration, signing in, specializations, booking or cancellation. I use predefined answers and do not access your personal records.")
    );

    public String answer(String message) {
        String text = Normalizer.normalize(message, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
        if (EMERGENCY.matcher(text).find()) {
            return "This chat cannot assess an emergency. For a possible emergency, contact local emergency services or go to the nearest emergency department now.";
        }
        if (MEDICAL.matcher(text).find()) {
            return "I cannot diagnose conditions, prescribe medicines, suggest dosages or recommend treatment. Please consult a qualified healthcare professional. "
                    + "I can explain how to use HMS and book a consultation.";
        }
        for (Faq faq : KNOWLEDGE_BASE) {
            if (faq.intent().matcher(text).find()) {
                return faq.response();
            }
        }
        return FALLBACK;
    }

    private static Faq faq(String expression, String response) {
        return new Faq(Pattern.compile(expression), response);
    }

    private record Faq(Pattern intent, String response) { }
}
