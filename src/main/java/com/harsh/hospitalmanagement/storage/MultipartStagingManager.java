package com.harsh.hospitalmanagement.storage;

import com.harsh.hospitalmanagement.exception.DocumentStorageUnavailableException;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.PointerByReference;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.sun.jna.platform.win32.AccCtrl.SE_OBJECT_TYPE.SE_FILE_OBJECT;

@Component
public class MultipartStagingManager {

    private static final Logger log = LoggerFactory.getLogger(MultipartStagingManager.class);
    public static final Duration DEFAULT_ABANDONED_AGE = Duration.ofHours(1);

    private final Path stagingDirectory;

    @Autowired
    public MultipartStagingManager(
            @Value("${hms.document-storage.multipart-staging-dir:${java.io.tmpdir}/hms-multipart-staging}") String stagingPath
    ) {
        this(stagingPath != null && !stagingPath.isBlank() ? Path.of(stagingPath) : null);
    }

    public MultipartStagingManager(Path stagingDirectory) {
        this.stagingDirectory = stagingDirectory != null ? stagingDirectory.toAbsolutePath().normalize() : null;
    }

    @PostConstruct
    public void init() {
        if (stagingDirectory != null) {
            validateStagingDirectory();
            cleanAbandonedFiles(DEFAULT_ABANDONED_AGE);
        }
    }

    public Path getStagingDirectory() {
        return stagingDirectory;
    }

    public void validateStagingDirectory() {
        if (stagingDirectory == null) {
            throw new DocumentStorageUnavailableException("Multipart staging directory is not configured");
        }
        if (!Files.exists(stagingDirectory)) {
            try {
                createRestrictedDirectory(stagingDirectory);
            } catch (IOException e) {
                throw new DocumentStorageUnavailableException("Failed to provision multipart staging directory: " + e.getMessage(), e);
            }
        }
        if (!Files.isDirectory(stagingDirectory)) {
            throw new DocumentStorageUnavailableException("Multipart staging path is not a directory: " + stagingDirectory);
        }
        verifyPermissions(stagingDirectory);
    }

    public static void createRestrictedDirectory(Path path) throws IOException {
        Files.createDirectories(path);
        if (Platform.isWindows()) {
            setRestrictedWindowsDacl(path);
        } else {
            try {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystem
            }
        }
    }

    public static void verifyPermissions(Path path) {
        if (Platform.isWindows()) {
            verifyWindowsPermissions(path);
        } else {
            verifyPosixPermissions(path);
        }
    }

    private static void verifyWindowsPermissions(Path path) {
        var token = new WinNT.HANDLEByReference();
        if (!Advapi32.INSTANCE.OpenProcessToken(Kernel32.INSTANCE.GetCurrentProcess(), WinNT.TOKEN_QUERY, token)) {
            throw new DocumentStorageUnavailableException("Failed to inspect Windows process token for staging security");
        }
        try {
            var account = Advapi32Util.getTokenAccount(token.getValue());
            Set<String> trustedSids = new HashSet<>(List.of(account.sidString, "S-1-5-18", "S-1-5-32-544"));

            try {
                setRestrictedWindowsDacl(path);
            } catch (IOException e) {
                throw new DocumentStorageUnavailableException("Insecure multipart staging permissions on " + path, e);
            }
        } finally {
            Kernel32Util.closeHandleRef(token);
        }
    }

    public static void setRestrictedWindowsDacl(Path path) throws IOException {
        var token = new WinNT.HANDLEByReference();
        if (!Advapi32.INSTANCE.OpenProcessToken(Kernel32.INSTANCE.GetCurrentProcess(), WinNT.TOKEN_QUERY, token)) {
            throw new IOException("Failed to open current process token");
        }
        try {
            var sid = new WinNT.PSID(Advapi32Util.getTokenAccount(token.getValue()).sid);
            var acl = new WinNT.ACL(1024);
            if (!Advapi32.INSTANCE.InitializeAcl(acl, 1024, WinNT.ACL_REVISION)) {
                throw new IOException("Failed to initialize ACL");
            }
            if (!Advapi32.INSTANCE.AddAccessAllowedAceEx(acl, WinNT.ACL_REVISION,
                    WinNT.OBJECT_INHERIT_ACE | WinNT.CONTAINER_INHERIT_ACE, WinNT.FILE_ALL_ACCESS, sid)) {
                throw new IOException("Failed to add ACE");
            }
            if (Advapi32.INSTANCE.SetNamedSecurityInfo(path.toString(), SE_FILE_OBJECT,
                    WinNT.DACL_SECURITY_INFORMATION | WinNT.PROTECTED_DACL_SECURITY_INFORMATION,
                    null, null, acl.getPointer(), null) != 0) {
                throw new IOException("Failed to set security info on staging directory");
            }
        } finally {
            Kernel32Util.closeHandleRef(token);
        }
    }

    private static void verifyPosixPermissions(Path path) {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            if (permissions.contains(PosixFilePermission.GROUP_READ)
                    || permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_READ)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                throw new DocumentStorageUnavailableException("Multipart staging directory must not be accessible to group or others: " + path);
            }
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem
        } catch (IOException e) {
            throw new DocumentStorageUnavailableException("Failed to check POSIX permissions on staging directory: " + path, e);
        }
    }

    public int cleanAbandonedFiles(Duration olderThan) {
        if (stagingDirectory == null || !Files.exists(stagingDirectory)) {
            return 0;
        }
        Duration threshold = olderThan != null ? olderThan : DEFAULT_ABANDONED_AGE;
        Instant cutoff = Instant.now().minus(threshold);
        int deletedCount = 0;

        try (Stream<Path> stream = Files.list(stagingDirectory)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                try {
                    String filename = file.getFileName().toString();
                    if (filename.endsWith(".tmp") || filename.startsWith("upload_")) {
                        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        if (attrs.isSymbolicLink() || !attrs.isRegularFile()) {
                            log.debug("Skipping non-regular file or symbolic link during staging cleanup: {}", file);
                            continue;
                        }
                        if (attrs.lastModifiedTime().toInstant().isBefore(cutoff)) {
                            Files.deleteIfExists(file);
                            deletedCount++;
                        }
                    }
                } catch (IOException e) {
                    log.warn("Failed to delete abandoned multipart staging file {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to list multipart staging directory for cleanup: {}", e.getMessage());
        }
        return deletedCount;
    }
}
