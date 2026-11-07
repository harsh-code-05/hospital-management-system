package com.harsh.hospitalmanagement.storage;

import com.harsh.hospitalmanagement.exception.DocumentStorageUnavailableException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MultipartStagingManagerTest {

    @Test
    void validateStagingDirectoryCreatesRestrictedDirectory(@TempDir Path tempDir) {
        Path staging = tempDir.resolve("sub-staging");
        MultipartStagingManager manager = new MultipartStagingManager(staging);

        assertThat(Files.exists(staging)).isFalse();
        manager.validateStagingDirectory();
        assertThat(Files.exists(staging)).isTrue();
        assertThat(Files.isDirectory(staging)).isTrue();
    }

    @Test
    void validateStagingDirectoryFailsWhenNull() {
        MultipartStagingManager manager = new MultipartStagingManager((Path) null);
        assertThatThrownBy(manager::validateStagingDirectory)
                .isInstanceOf(DocumentStorageUnavailableException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void validateStagingDirectoryFailsWhenNotDirectory(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("some-file.txt");
        Files.writeString(file, "hello");

        MultipartStagingManager manager = new MultipartStagingManager(file);
        assertThatThrownBy(manager::validateStagingDirectory)
                .isInstanceOf(DocumentStorageUnavailableException.class)
                .hasMessageContaining("not a directory");
    }

    @Test
    void cleanAbandonedFilesDeletesOldTmpFilesAndPreservesRecent(@TempDir Path tempDir) throws IOException {
        MultipartStagingManager manager = new MultipartStagingManager(tempDir);

        Path oldTmp = tempDir.resolve("upload_12345.tmp");
        Files.writeString(oldTmp, "old content");
        Files.setLastModifiedTime(oldTmp, FileTime.from(Instant.now().minus(Duration.ofHours(2))));

        Path recentTmp = tempDir.resolve("upload_67890.tmp");
        Files.writeString(recentTmp, "recent content");
        Files.setLastModifiedTime(recentTmp, FileTime.from(Instant.now().minus(Duration.ofMinutes(10))));

        Path regularFile = tempDir.resolve("keep_me.txt");
        Files.writeString(regularFile, "important text");
        Files.setLastModifiedTime(regularFile, FileTime.from(Instant.now().minus(Duration.ofHours(2))));

        int deleted = manager.cleanAbandonedFiles(Duration.ofHours(1));

        assertThat(deleted).isEqualTo(1);
        assertThat(Files.exists(oldTmp)).isFalse();
        assertThat(Files.exists(recentTmp)).isTrue();
        assertThat(Files.exists(regularFile)).isTrue();
    }

    @Test
    void cleanAbandonedFilesSkipsSymbolicLinksAndNeverTouchesTarget(@TempDir Path tempDir) throws IOException {
        Path externalDir = tempDir.resolve("external");
        Files.createDirectories(externalDir);
        Path externalTarget = externalDir.resolve("target_secret.txt");
        String initialContent = "confidential external evidence";
        Files.writeString(externalTarget, initialContent);
        Instant oldTime = Instant.now().minus(Duration.ofHours(3));
        Files.setLastModifiedTime(externalTarget, FileTime.from(oldTime));

        Path stagingDir = tempDir.resolve("staging");
        Files.createDirectories(stagingDir);

        Path symlinkInStaging = stagingDir.resolve("upload_trap.tmp");
        try {
            Files.createSymbolicLink(symlinkInStaging, externalTarget);
        } catch (UnsupportedOperationException | FileSystemException | SecurityException e) {
            Assumptions.assumeTrue(false,
                    "Skipping symlink test: symbolic link creation not supported or requires elevation on this platform: " + e.getMessage());
            return;
        }

        Path validStaleTmp = stagingDir.resolve("upload_stale.tmp");
        Files.writeString(validStaleTmp, "stale content");
        Files.setLastModifiedTime(validStaleTmp, FileTime.from(Instant.now().minus(Duration.ofHours(2))));

        MultipartStagingManager manager = new MultipartStagingManager(stagingDir);
        int deleted = manager.cleanAbandonedFiles(Duration.ofHours(1));

        assertThat(deleted).isEqualTo(1);
        assertThat(Files.exists(validStaleTmp)).isFalse();

        // Verify external target remains completely untouched
        assertThat(Files.exists(externalTarget)).isTrue();
        assertThat(Files.readString(externalTarget)).isEqualTo(initialContent);
        assertThat(Files.getLastModifiedTime(externalTarget)).isEqualTo(FileTime.from(oldTime));

        // Verify symlink was not followed or deleted
        assertThat(Files.exists(symlinkInStaging, LinkOption.NOFOLLOW_LINKS)).isTrue();
    }

    @Test
    void cleanAbandonedFilesSkipsNonRegularDirectories(@TempDir Path tempDir) throws IOException {
        Path stagingDir = tempDir.resolve("staging");
        Files.createDirectories(stagingDir);

        Path subDir = stagingDir.resolve("upload_sub.tmp");
        Files.createDirectories(subDir);
        Files.setLastModifiedTime(subDir, FileTime.from(Instant.now().minus(Duration.ofHours(2))));

        MultipartStagingManager manager = new MultipartStagingManager(stagingDir);
        int deleted = manager.cleanAbandonedFiles(Duration.ofHours(1));

        assertThat(deleted).isEqualTo(0);
        assertThat(Files.exists(subDir)).isTrue();
    }
}
