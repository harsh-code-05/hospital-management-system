package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.exception.ForbiddenException;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import com.harsh.hospitalmanagement.repository.UserRepository;
import com.harsh.hospitalmanagement.security.LocalAccount;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
class DoctorManagementAccess {

    private final DoctorRepository doctors;
    private final UserRepository users;

    DoctorManagementAccess(DoctorRepository doctors, UserRepository users) {
        this.doctors = doctors;
        this.users = users;
    }

    Access requireManager(Long doctorId, Authentication authentication) {
        User actor = users.findById(LocalAccount.require(authentication).id())
                .orElseThrow(() -> new ForbiddenException("Doctor or admin access is required"));
        Role role = actor.getRole();
        if ((role != Role.DOCTOR && role != Role.ADMIN) || authentication.getAuthorities().stream()
                .noneMatch(authority -> authority.getAuthority().equals("ROLE_" + role.name()))) {
            throw new ForbiddenException("Doctor or admin access is required");
        }

        Doctor doctor = doctors.findByIdForUpdate(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found"));
        if (role == Role.DOCTOR && !actor.getId().equals(doctor.getUser().getId())) {
            throw new ForbiddenException("You are not allowed to manage this doctor");
        }
        return new Access(doctor, actor);
    }

    record Access(Doctor doctor, User actor) {
    }
}
