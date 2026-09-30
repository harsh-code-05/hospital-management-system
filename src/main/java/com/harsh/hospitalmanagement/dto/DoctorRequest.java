package com.harsh.hospitalmanagement.dto;

public class DoctorRequest {

    private Long userId;
    private Long specializationId;
    private String firstName;
    private String lastName;
    private String qualification;
    private Integer experience;

    public DoctorRequest() {
    }

    public DoctorRequest(Long userId, Long specializationId,
                         String firstName, String lastName,
                         String qualification, Integer experience) {
        this.userId = userId;
        this.specializationId = specializationId;
        this.firstName = firstName;
        this.lastName = lastName;
        this.qualification = qualification;
        this.experience = experience;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getSpecializationId() {
        return specializationId;
    }

    public void setSpecializationId(Long specializationId) {
        this.specializationId = specializationId;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getQualification() {
        return qualification;
    }

    public void setQualification(String qualification) {
        this.qualification = qualification;
    }

    public Integer getExperience() {
        return experience;
    }

    public void setExperience(Integer experience) {
        this.experience = experience;
    }
}