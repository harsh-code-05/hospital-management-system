package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.service.SpecializationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/specializations")
public class SpecializationController {

    private final SpecializationService specializationService;

    public SpecializationController(SpecializationService specializationService) {
        this.specializationService = specializationService;
    }

    @PostMapping
    public ResponseEntity<Specialization> createSpecialization(
            @RequestBody Specialization specialization) {

        Specialization savedSpecialization =
                specializationService.saveSpecialization(specialization);

        return ResponseEntity.ok(savedSpecialization);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Specialization> getSpecialization(
            @PathVariable Long id) {

        return ResponseEntity.ok(
                specializationService.getSpecializationById(id)
        );
    }
}