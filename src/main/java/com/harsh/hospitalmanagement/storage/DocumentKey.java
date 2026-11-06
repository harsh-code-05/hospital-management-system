package com.harsh.hospitalmanagement.storage;

import com.harsh.hospitalmanagement.service.DoctorVerificationService.DocumentKind;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Internal logical address. A storage key is neither a user input nor a filesystem path. */
public record DocumentKey(long doctorId, DocumentKind kind, UUID version) {

    private static final Pattern KEY = Pattern.compile(
            "verification/([1-9][0-9]{0,18})/(id-proof|qualification)/"
                    + "([0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})");

    public DocumentKey {
        if (doctorId <= 0) {
            throw new IllegalArgumentException("Doctor ID must be positive");
        }
        Objects.requireNonNull(kind);
        Objects.requireNonNull(version);
    }

    public static DocumentKey parse(String value) {
        if (value == null || value.length() > 83) {
            throw new IllegalArgumentException("Invalid verification document key");
        }
        var match = KEY.matcher(value);
        if (!match.matches()) {
            throw new IllegalArgumentException("Invalid verification document key");
        }
        try {
            var kind = match.group(2).equals(DocumentKind.ID_PROOF.pathSegment())
                    ? DocumentKind.ID_PROOF : DocumentKind.QUALIFICATION;
            return new DocumentKey(Long.parseLong(match.group(1)), kind, UUID.fromString(match.group(3)));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid verification document key");
        }
    }

    Path relativePath() {
        return Path.of("verification", Long.toString(doctorId), kind.pathSegment(), version.toString());
    }

    @Override
    public String toString() {
        return "verification/" + doctorId + "/" + kind.pathSegment() + "/" + version;
    }
}
