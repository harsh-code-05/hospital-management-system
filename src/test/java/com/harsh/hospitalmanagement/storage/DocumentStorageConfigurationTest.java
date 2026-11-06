package com.harsh.hospitalmanagement.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentStorageConfigurationTest {

    @TempDir Path temp;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DocumentStorageConfiguration.class);

    @Test
    void storageIsDisabledUnlessExplicitlyEnabled() {
        runner.withPropertyValues("hms.document-storage.root=" + temp.resolve("missing"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(DocumentStorage.class);
                    assertThat(context).doesNotHaveBean(VerificationImageValidator.class);
                });
    }

    @Test
    void enabledStorageRequiresExplicitKeyConfiguration() {
        runner.withPropertyValues("hms.document-storage.enabled=true")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Private storage requires a DPAPI key directory and a positive master-key version"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void refusesAKeyDirectoryOnTheDocumentVolume() throws IOException {
        Path documents = WindowsDocumentStorageTest.protectedDirectory(temp.resolve("docs"));
        Path keys = WindowsDocumentStorageTest.protectedDirectory(temp.resolve("keys"));
        runner.withPropertyValues("hms.document-storage.enabled=true",
                        "hms.document-storage.root=" + documents,
                        "hms.document-storage.key-directory=" + keys,
                        "hms.document-storage.master-key-version=1")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("The DPAPI master-key directory must be on a separate NTFS volume"));
    }
}
