package com.harsh.hospitalmanagement.entity;

public final class DoctorVerificationDocumentPolicy {

    public static final int MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024; // 5 MiB = 5,242,880 bytes

    private DoctorVerificationDocumentPolicy() {
    }

    public static void validateFileSize(long fileSize) {
        if (fileSize <= 0) {
            throw new IllegalArgumentException("File size must be positive");
        }
        if (fileSize > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException("File size exceeds 5 MiB maximum limit: " + fileSize);
        }
    }
}
