package com.harsh.hospitalmanagement.repository;

import com.harsh.hospitalmanagement.entity.AppointmentSlot;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;


public interface AppointmentSlotRepository
        extends JpaRepository<AppointmentSlot, Long> {

    String BOOKABLE = """
            s.status = com.harsh.hospitalmanagement.enums.SlotStatus.AVAILABLE
            AND s.retired = false AND s.availabilityRevision = a.revision
            AND s.doctor.id = a.doctor.id AND s.date = a.date
            AND s.startTime >= a.startTime AND s.endTime <= a.endTime
            AND s.startTime < s.endTime
            """;

    String DISCOVERY_JOINS = """
             FROM AppointmentSlot s JOIN s.availability a JOIN s.doctor d JOIN d.user u
             LEFT JOIN s.location l LEFT JOIN a.location al
             WHERE
            """;

    String DISCOVERY_TERMS = """
            AND s.offerVersion IS NOT NULL AND s.fee IS NOT NULL
            AND d.verificationStatus = com.harsh.hospitalmanagement.enums.VerificationStatus.VERIFIED
            AND u.role = com.harsh.hospitalmanagement.enums.Role.DOCTOR
            AND s.consultationMode = a.consultationMode
            AND (
                (s.consultationMode = com.harsh.hospitalmanagement.enums.ConsultationMode.IN_CLINIC
                    AND l.id = al.id AND l.doctor.id = d.id AND l.active = true AND l.archived = false
                    AND s.fee = l.inClinicFee)
                OR (s.consultationMode = com.harsh.hospitalmanagement.enums.ConsultationMode.VIDEO
                    AND l.id IS NULL AND al.id IS NULL AND s.fee = d.videoConsultationFee)
            )
            """;

    @Query("SELECT s FROM AppointmentSlot s JOIN s.availability a WHERE s.doctor.id = :doctorId "
            + "AND s.offerVersion IS NULL AND " + BOOKABLE)
    List<AppointmentSlot> findBookableByDoctor(@Param("doctorId") Long doctorId);

    @Query("SELECT s FROM AppointmentSlot s JOIN s.availability a WHERE s.doctor.id = :doctorId "
            + "AND s.date = :date AND s.offerVersion IS NULL AND " + BOOKABLE)
    List<AppointmentSlot> findBookableByDoctorAndDate(@Param("doctorId") Long doctorId, @Param("date") LocalDate date);

    @Query("SELECT s" + DISCOVERY_JOINS + "s.doctor.id = :doctorId AND s.date = :date AND "
            + BOOKABLE + DISCOVERY_TERMS + " ORDER BY s.startTime, s.id")
    List<AppointmentSlot> findDiscoverableOffers(@Param("doctorId") Long doctorId, @Param("date") LocalDate date);

    @Query("SELECT COUNT(s) > 0" + DISCOVERY_JOINS + "s.id = :id AND " + BOOKABLE + DISCOVERY_TERMS)
    boolean isDiscoverableOffer(@Param("id") Long id);

    @Query("SELECT COUNT(s) > 0 FROM AppointmentSlot s JOIN s.availability a WHERE s.id = :id AND " + BOOKABLE)
    boolean isBookable(@Param("id") Long id);

    @Query("""
            SELECT COUNT(s) > 0 FROM AppointmentSlot s JOIN s.availability a
            WHERE s.doctor.id = :doctorId AND s.date = :date
            AND s.startTime < :endTime AND s.endTime > :startTime AND
            """ + BOOKABLE)
    boolean existsOverlappingBookableSlot(
            @Param("doctorId") Long doctorId, @Param("date") LocalDate date,
            @Param("startTime") LocalTime startTime, @Param("endTime") LocalTime endTime);

    @Query("""
            SELECT s FROM AppointmentSlot s
            WHERE s.availability.id = :availabilityId AND s.availabilityRevision = :revision AND s.retired = false
            """)
    List<AppointmentSlot> findUnretiredForRevision(
            @Param("availabilityId") Long availabilityId, @Param("revision") long revision);

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE AppointmentSlot s SET s.retired = true
            WHERE s.doctor.id = :doctorId AND s.location.id = :locationId AND s.retired = false
            """)
    void retireForLocation(@Param("doctorId") Long doctorId, @Param("locationId") Long locationId);

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE AppointmentSlot s SET s.retired = true
            WHERE s.doctor.id = :doctorId AND s.retired = false
            AND s.consultationMode = com.harsh.hospitalmanagement.enums.ConsultationMode.VIDEO
            """)
    void retireVideoOffers(@Param("doctorId") Long doctorId);

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE AppointmentSlot s SET s.retired = true
            WHERE s.doctor.id = :doctorId AND s.offerVersion IS NOT NULL AND s.retired = false
            """)
    void retireVersionedOffers(@Param("doctorId") Long doctorId);

    @Query("SELECT s.doctor.id FROM AppointmentSlot s WHERE s.id = :id")
    Optional<Long> findDoctorIdById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM AppointmentSlot s WHERE s.id = :id")
    Optional<AppointmentSlot> findByIdForUpdate(@Param("id") Long id);


    Optional<AppointmentSlot> findByDoctor_IdAndDateAndStartTimeAndEndTime(
            Long doctorId, LocalDate date, LocalTime startTime, LocalTime endTime);
}
