package com.harsh.hospitalmanagement.storage;

import com.harsh.hospitalmanagement.service.DoctorVerificationService.DocumentKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentKeyTest {

    private static final String VERSION = "32fe7d9e-9fa0-43e7-8748-f26d5b79314f";

    @Test
    void roundTripsCanonicalKeyWithLargestDoctorId() {
        var key = new DocumentKey(Long.MAX_VALUE, DocumentKind.QUALIFICATION, UUID.fromString(VERSION));
        assertThat(key.toString()).isEqualTo("verification/9223372036854775807/qualification/" + VERSION);
        assertThat(DocumentKey.parse(key.toString())).isEqualTo(key);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "verification/0/id-proof/" + VERSION,
            "verification/01/id-proof/" + VERSION,
            "verification/9223372036854775808/id-proof/" + VERSION,
            "verification/1/other/" + VERSION,
            "verification/1/id-proof/1-1-1-1-1",
            "verification/1/id-proof/32FE7D9E-9FA0-43E7-8748-F26D5B79314F",
            "verification/1/id-proof/" + VERSION + ":secret",
            "verification/1/id-proof/" + VERSION + "/",
            "verification/1/id-proof/../" + VERSION,
            "verification/1/id-proof/%2e%2e%2f" + VERSION,
            "C:\\verification\\1\\id-proof\\" + VERSION,
            "/verification/1/id-proof/" + VERSION
    })
    void rejectsNoncanonicalKeysAndPaths(String value) {
        assertThatThrownBy(() -> DocumentKey.parse(value)).isInstanceOf(IllegalArgumentException.class);
    }
}
