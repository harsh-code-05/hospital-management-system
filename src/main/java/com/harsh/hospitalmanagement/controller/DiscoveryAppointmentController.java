package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.dto.DoctorDiscoveryResponse;
import com.harsh.hospitalmanagement.enums.AppointmentStatus;
import com.harsh.hospitalmanagement.enums.ConsultationMode;
import com.harsh.hospitalmanagement.security.LocalAccount;
import com.harsh.hospitalmanagement.service.AppointmentService;
import com.harsh.hospitalmanagement.service.AppointmentSlotService;
import com.harsh.hospitalmanagement.service.DoctorDiscoveryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v2")
public class DiscoveryAppointmentController {

    private final AppointmentSlotService slots;
    private final AppointmentService appointments;
    private final DoctorDiscoveryService discoveryService;

    public DiscoveryAppointmentController(
            AppointmentSlotService slots,
            AppointmentService appointments,
            DoctorDiscoveryService discoveryService
    ) {
        this.slots = slots;
        this.appointments = appointments;
        this.discoveryService = discoveryService;
    }

    @GetMapping("/discovery/doctors")
    public List<DoctorDiscoveryResponse> discoverDoctors(
            @RequestParam(required = false) Long specializationId,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String mode
    ) {
        return discoveryService.discoverDoctors(specializationId, city, mode);
    }

    @GetMapping("/discovery/doctors/{doctorId}/slots")
    public List<SlotOffer> getOffers(@PathVariable Long doctorId, @RequestParam LocalDate date) {
        return slots.getDiscoverableOffers(doctorId, date).stream()
                .map(slot -> new SlotOffer(slot.getId(), slot.getDate(), slot.getStartTime(), slot.getEndTime(),
                        slot.getLocation() == null ? null : slot.getLocation().getId(), slot.getConsultationMode(),
                        slot.getFee().toPlainString(), "INR", slot.getOfferVersion()))
                .toList();
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<Map<String, Object>> handleMissingParameter(MissingServletRequestParameterException failure) {
        return invalidParameter(failure.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, Object>> handleInvalidParameter(MethodArgumentTypeMismatchException failure) {
        return invalidParameter(failure.getName());
    }

    private ResponseEntity<Map<String, Object>> invalidParameter(String parameter) {
        String message = "date".equals(parameter)
                ? "Query parameter 'date' is required and must be a valid date in YYYY-MM-DD format."
                : "Invalid value for parameter '" + parameter + "'.";
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("status", 400, "message", message));
    }

    @PostMapping("/appointments")
    public BookingResponse book(@Valid @RequestBody BookingRequest request, Authentication authentication) {
        var appointment = appointments.bookVersionedOffer(request.slotId(), request.expectedDate(),
                request.offerVersion(), request.reason(), LocalAccount.require(authentication));
        return new BookingResponse(appointment.getId(), appointment.getStatus(),
                appointment.getSlot().getId(), appointment.getSlot().getDate(),
                appointment.getLocation() == null ? null : appointment.getLocation().getId(),
                appointment.getConsultationMode(), appointment.getClinicNameSnapshot(),
                appointment.getClinicAddressSnapshot(), appointment.getConsultationFee(), appointment.getOfferVersion());
    }

    public record SlotOffer(Long slotId, LocalDate date, LocalTime startTime, LocalTime endTime,
                            Long locationId, ConsultationMode consultationMode, String fee, String currency,
                            UUID offerVersion) {
    }

    public record BookingRequest(@NotNull Long slotId, @NotNull LocalDate expectedDate,
                                 @NotNull UUID offerVersion, @NotBlank @Size(max = 255) String reason) {
    }

    public record BookingResponse(Long appointmentId, AppointmentStatus status, Long slotId, LocalDate date,
                                  Long locationId, ConsultationMode consultationMode, String clinicName,
                                  String clinicAddress, BigDecimal consultationFee, UUID offerVersion) {
    }
}
