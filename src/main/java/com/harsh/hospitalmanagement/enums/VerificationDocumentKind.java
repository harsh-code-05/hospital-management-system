package com.harsh.hospitalmanagement.enums;

import com.harsh.hospitalmanagement.service.DoctorVerificationService;

public enum VerificationDocumentKind {
    ID_PROOF("id-proof"),
    QUALIFICATION("qualification");

    private final String pathSegment;

    VerificationDocumentKind(String pathSegment) {
        this.pathSegment = pathSegment;
    }

    public String pathSegment() {
        return pathSegment;
    }

    public static VerificationDocumentKind fromServiceKind(DoctorVerificationService.DocumentKind kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case ID_PROOF -> ID_PROOF;
            case QUALIFICATION -> QUALIFICATION;
        };
    }

    public DoctorVerificationService.DocumentKind toServiceKind() {
        return switch (this) {
            case ID_PROOF -> DoctorVerificationService.DocumentKind.ID_PROOF;
            case QUALIFICATION -> DoctorVerificationService.DocumentKind.QUALIFICATION;
        };
    }
}
