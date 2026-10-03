package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.dto.DoctorAvailabilityRequest;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.DoctorAvailability;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.repository.DoctorAvailabilityRepository;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import org.springframework.stereotype.Service;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;

import java.time.LocalDate;

@Service
public class DoctorAvailabilityService {

    private final DoctorAvailabilityRepository doctorAvailabilityRepository;
    private final DoctorRepository doctorRepository;

    public DoctorAvailabilityService(
            DoctorAvailabilityRepository doctorAvailabilityRepository,
            DoctorRepository doctorRepository) {

        this.doctorAvailabilityRepository = doctorAvailabilityRepository;
        this.doctorRepository = doctorRepository;
    }

    public DoctorAvailability getAvailabilityById(Long id) {
        return doctorAvailabilityRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor availability not found"));
    }

    public DoctorAvailability saveAvailability(DoctorAvailability availability) {
        return doctorAvailabilityRepository.save(availability);
    }

    public DoctorAvailability createAvailability(
            DoctorAvailabilityRequest request) {

        Doctor doctor = doctorRepository.findById(request.getDoctorId())
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found"));


        if (!request.getStartTime().isBefore(request.getEndTime())) {
            throw new BadRequestException(
                    "Start time must be before end time"
            );
        }
        if (request.getDate().isBefore(LocalDate.now())) {
            throw new BadRequestException("Availability date cannot be in the past");
        }


        boolean overlapExists =
                doctorAvailabilityRepository.existsOverlappingAvailability(
                        request.getDoctorId(),
                        request.getDate(),
                        request.getStartTime(),
                        request.getEndTime()
                );

        if (overlapExists) {
            throw new BadRequestException(
                    "Doctor already has availability during this time");
        }


        DoctorAvailability availability = new DoctorAvailability();

        availability.setDoctor(doctor);
        availability.setDate(request.getDate());
        availability.setStartTime(request.getStartTime());
        availability.setEndTime(request.getEndTime());

        return doctorAvailabilityRepository.save(availability);
    }
}