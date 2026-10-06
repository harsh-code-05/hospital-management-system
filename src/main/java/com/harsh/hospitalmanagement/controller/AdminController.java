package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.dto.AdminUserRequest;
import com.harsh.hospitalmanagement.dto.UserResponse;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final UserService userService;

    public AdminController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/users")
    public ResponseEntity<UserResponse> createStaffUser(
            @Valid @RequestBody AdminUserRequest request) {

        User user = userService.createStaffUser(request);

        UserResponse response = new UserResponse();
        response.setId(user.getId());
        response.setEmail(user.getEmail());
        response.setRole(user.getRole());

        return ResponseEntity.ok(response);
    }
}