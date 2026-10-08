package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.dto.UserRequest;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.exception.ForbiddenException;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import com.harsh.hospitalmanagement.dto.AdminUserRequest;


@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder) {

        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public User getUserByEmail(String email) {

        return userRepository.findByEmail(email)
                .orElseThrow(() ->
                        new ResourceNotFoundException("User not found"));
    }

    public User createUser(UserRequest request) {

        if (request.getRole() != Role.PATIENT) {
            throw new ForbiddenException(
                    "Public registration is allowed only for PATIENT role");
        }

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BadRequestException("Email already exists");
        }

        User user = new User();

        user.setEmail(request.getEmail());

        user.setPassword(
                passwordEncoder.encode(request.getPassword())
        );

        user.setRole(request.getRole());

        return userRepository.save(user);
    }
    public User createStaffUser(AdminUserRequest request) {

        if (request.getRole() != Role.DOCTOR &&
                request.getRole() != Role.ADMIN) {

            throw new BadRequestException(
                    "Staff role must be DOCTOR or ADMIN");
        }

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BadRequestException("Email already exists");
        }

        User user = new User();

        user.setEmail(request.getEmail());

        user.setPassword(
                passwordEncoder.encode(request.getPassword())
        );

        user.setRole(request.getRole());

        return userRepository.save(user);
    }

    public User findOrCreateGoogleUser(
            String email,
            String googleId) {

        if (email == null || email.isBlank()) {
            throw new BadRequestException("Email is required");
        }
        if (googleId == null || googleId.isBlank()) {
            throw new BadRequestException("Google ID is required");
        }

        return userRepository.findByGoogleId(googleId)
                .orElseGet(() -> {

                    User existingUser = userRepository
                            .findByEmail(email)
                            .orElse(null);

                    if (existingUser != null) {
                        if (existingUser.getGoogleId() != null && !existingUser.getGoogleId().equals(googleId)) {
                            throw new BadRequestException(
                                    "Account is already linked to a different Google account");
                        }
                        existingUser.setGoogleId(googleId);
                        return userRepository.save(existingUser);
                    }

                    User user = new User();

                    user.setEmail(email);
                    user.setPassword(null);
                    user.setRole(Role.PATIENT);
                    user.setGoogleId(googleId);

                    return userRepository.save(user);
                });
    }
}