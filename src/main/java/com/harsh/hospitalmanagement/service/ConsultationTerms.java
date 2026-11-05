package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.entity.AppointmentSlot;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.DoctorLocation;
import com.harsh.hospitalmanagement.enums.ConsultationMode;
import com.harsh.hospitalmanagement.exception.BadRequestException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

record ConsultationTerms(DoctorLocation location, ConsultationMode mode, BigDecimal fee) {

    private static final BigDecimal MAX_FEE = new BigDecimal("9999999999.99");

    static BigDecimal requireFee(BigDecimal fee) {
        if (fee == null || fee.signum() < 0 || fee.compareTo(MAX_FEE) > 0
                || fee.stripTrailingZeros().scale() > 2) {
            throw new BadRequestException("An explicit non-negative consultation fee with at most two decimals is required");
        }
        return fee.setScale(2, RoundingMode.UNNECESSARY);
    }

    static ConsultationTerms forSchedule(Doctor doctor, ConsultationMode mode, DoctorLocation location) {
        if (mode == ConsultationMode.VIDEO) {
            if (location != null) {
                throw new BadRequestException("Video availability cannot have a clinic location");
            }
            return new ConsultationTerms(null, mode, requireFee(doctor.getVideoConsultationFee()));
        }
        if (location == null) {
            // Older availability rows have no discovery terms.
            return new ConsultationTerms(null, null, null);
        }
        if (!Objects.equals(doctor.getId(), location.getDoctor().getId())
                || !location.isActive() || location.isArchived()) {
            throw new BadRequestException("An active clinic owned by the doctor is required");
        }
        return new ConsultationTerms(location, ConsultationMode.IN_CLINIC, requireFee(location.getInClinicFee()));
    }

    void publishOn(AppointmentSlot slot) {
        slot.setLocation(location);
        slot.setConsultationMode(mode);
        slot.setFee(fee);
        slot.setOfferVersion(mode == null ? null : UUID.randomUUID());
        slot.setRetired(false);
    }
}
