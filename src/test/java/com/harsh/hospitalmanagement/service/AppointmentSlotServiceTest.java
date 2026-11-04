package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.entity.AppointmentSlot;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.DoctorAvailability;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.SlotStatus;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.repository.AppointmentSlotRepository;
import com.harsh.hospitalmanagement.repository.DoctorAvailabilityRepository;
import com.harsh.hospitalmanagement.security.LocalAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppointmentSlotServiceTest {

    private static final long AVAILABILITY_ID = 10L;
    private static final long DOCTOR_ID = 20L;
    private static final LocalDate DATE = LocalDate.of(2030, 1, 15);
    private static final LocalAccount DOCTOR_ACCOUNT = new LocalAccount(30L, "doctor@test.invalid");

    @Mock
    private AppointmentSlotRepository slotRepository;

    @Mock
    private DoctorAvailabilityRepository availabilityRepository;

    private AppointmentSlotService service;
    private Doctor doctor;

    @BeforeEach
    void setUp() {
        service = new AppointmentSlotService(slotRepository, availabilityRepository);
        User user = new User();
        user.setId(DOCTOR_ACCOUNT.id());
        doctor = new Doctor();
        doctor.setId(DOCTOR_ID);
        doctor.setUser(user);
    }

    @ParameterizedTest
    @CsvSource({
            "22:30, 23:59, 22:30-23:00;23:00-23:30",
            "22:30, 23:30, 22:30-23:00;23:00-23:30",
            "23:29, 23:59, 23:29-23:59",
            "23:30, 23:59, ''",
            "09:15, 10:20, 09:15-09:45;09:45-10:15"
    })
    void generatesOnlyFullSlotsWithinTheSameDay(String start, String end, String intervals) {
        givenAvailability(start, end);
        List<String> expected = intervals.isEmpty() ? List.of() : List.of(intervals.split(";"));
        List<String> checked = new ArrayList<>();
        // The lookup guard is intentionally unused when the window fits no slots.
        lenient().when(slotRepository.existsByDoctorAndDateAndTime(eq(DOCTOR_ID), eq(DATE), any(), any()))
                .thenAnswer(invocation -> {
                    // Stop a wraparound regression at its first extra lookup.
                    assertThat(checked.size()).as("number of slot lookups").isLessThan(expected.size());
                    LocalTime slotStart = invocation.getArgument(2);
                    LocalTime slotEnd = invocation.getArgument(3);
                    checked.add(slotStart + "-" + slotEnd);
                    return false;
                });
        if (!expected.isEmpty()) {
            when(slotRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        }

        List<AppointmentSlot> slots = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> service.generateSlots(AVAILABILITY_ID, DOCTOR_ACCOUNT, false));

        assertThat(checked).containsExactlyElementsOf(expected);
        assertThat(slots).extracting(slot -> slot.getStartTime() + "-" + slot.getEndTime())
                .containsExactlyElementsOf(expected);
        assertThat(slots).allSatisfy(slot -> {
            assertThat(slot.getDate()).isEqualTo(DATE);
            assertThat(slot.getDoctor()).isSameAs(doctor);
            assertThat(slot.getStatus()).isEqualTo(SlotStatus.AVAILABLE);
        });
        if (expected.isEmpty()) {
            verify(slotRepository, never()).saveAll(any());
        } else {
            verify(slotRepository).saveAll(slots);
        }
    }

    @ParameterizedTest
    @CsvSource({"22:30, 00:30", "09:00, 09:00"})
    void rejectsUnsupportedOvernightOrZeroLengthAvailability(String start, String end) {
        givenAvailability(start, end);

        assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                assertThatThrownBy(() -> service.generateSlots(AVAILABILITY_ID, DOCTOR_ACCOUNT, false))
                        .isInstanceOf(BadRequestException.class)
                        .hasMessage("Start time must be before end time"));
        verifyNoInteractions(slotRepository);
    }

    private void givenAvailability(String start, String end) {
        DoctorAvailability availability = new DoctorAvailability();
        availability.setId(AVAILABILITY_ID);
        availability.setDoctor(doctor);
        availability.setDate(DATE);
        availability.setStartTime(LocalTime.parse(start));
        availability.setEndTime(LocalTime.parse(end));
        when(availabilityRepository.findById(AVAILABILITY_ID)).thenReturn(Optional.of(availability));
    }
}
