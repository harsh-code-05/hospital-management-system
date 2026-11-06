package com.harsh.hospitalmanagement.storage;

import com.harsh.hospitalmanagement.service.DoctorVerificationService.DocumentKind;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.platform.win32.WinCrypt;
import com.sun.jna.platform.win32.WinNT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.sun.jna.platform.win32.AccCtrl.SE_OBJECT_TYPE.SE_FILE_OBJECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@EnabledOnOs(OS.WINDOWS)
class WindowsDocumentStorageTest {

    @TempDir Path temp;

    private Path documentRoot;
    private WindowsPrivateFiles documentFiles;
    private WindowsPrivateFiles keyFiles;
    private VerificationImageValidator.ValidatedImage image;

    @BeforeEach
    void setUp() throws IOException {
        documentRoot = protectedDirectory(temp.resolve("documents"));
        documentFiles = new WindowsPrivateFiles(documentRoot);
        keyFiles = new WindowsPrivateFiles(protectedDirectory(temp.resolve("keys")));
        provision(keyFiles, 1);
        image = new VerificationImageValidator().validate("image/png",
                new ByteArrayInputStream(VerificationImageValidatorTest.imageBytes("png")));
    }

    @Test
    void storesAndReadsEncryptedDocumentsUsingPersistedDpapiKeys() throws IOException {
        var store = storage(1);
        var key = store.store(42, DocumentKind.ID_PROOF, image);
        var second = store.store(42, DocumentKind.ID_PROOF, image);
        byte[] encrypted = Files.readAllBytes(documentRoot.resolve(key.relativePath()));
        assertThat(key.toString()).startsWith("verification/42/id-proof/");
        assertThat(second).isNotEqualTo(key);
        assertThat(new String(encrypted, StandardCharsets.ISO_8859_1))
                .doesNotContain(new String(image.encoded(), StandardCharsets.ISO_8859_1));
        assertThat(encrypted).isNotEqualTo(Files.readAllBytes(documentRoot.resolve(second.relativePath())));
        var reopened = storage(1);
        assertThat(reopened.exists(key)).isTrue();
        reopened.verifyIntegrity(key);
        var document = reopened.read(key);
        assertThat(document.contentType()).isEqualTo("image/png");
        assertThat(document.downloadFilename()).isEqualTo("id-proof.png");
        assertThat(document.bytes()).isEqualTo(image.encoded());
        assertThat(reopened.delete(key)).isTrue();
        assertThat(reopened.delete(key)).isFalse();
        assertThat(reopened.exists(key)).isFalse();
        assertThat(reopened.exists(second)).isTrue();
    }

    @Test
    void tamperedCiphertextCannotBeReadOrVerified() throws IOException {
        var store = storage(1);
        var key = store.store(42, DocumentKind.ID_PROOF, image);
        Path target = documentRoot.resolve(key.relativePath());
        byte[] encrypted = Files.readAllBytes(target);
        encrypted[encrypted.length - 1] ^= 1;
        Files.write(target, encrypted);
        assertThatThrownBy(() -> store.read(key)).isInstanceOf(IOException.class).hasMessageContaining("integrity");
        assertThatThrownBy(() -> store.verifyIntegrity(key)).isInstanceOf(IOException.class);
    }

    @Test
    void ciphertextCannotBeMovedToAnotherLogicalKey() throws IOException {
        var store = storage(1);
        var first = store.store(42, DocumentKind.ID_PROOF, image);
        var second = store.store(43, DocumentKind.QUALIFICATION, image);
        Files.write(documentRoot.resolve(second.relativePath()), Files.readAllBytes(documentRoot.resolve(first.relativePath())));
        assertThatThrownBy(() -> store.read(second)).isInstanceOf(IOException.class).hasMessageContaining("integrity");
    }

    @Test
    void keyRotationKeepsOldVersionsReadableAndRejectsWrongKey() throws IOException {
        var old = storage(1).store(42, DocumentKind.ID_PROOF, image);
        provision(keyFiles, 2);
        var rotated = storage(2);
        var newer = rotated.store(42, DocumentKind.QUALIFICATION, image);
        assertThat(rotated.read(old).bytes()).isEqualTo(image.encoded());
        assertThat(keyFiles.delete(Path.of("v1.dpapi"))).isTrue();
        provision(keyFiles, 1);
        assertThatThrownBy(() -> rotated.read(old)).isInstanceOf(IOException.class).hasMessageContaining("integrity");
        assertThat(rotated.read(newer).bytes()).isEqualTo(image.encoded());
    }

    @Test
    void refusesMissingPlaintextAndWrongVersionKeyBlobs() throws IOException {
        assertThatThrownBy(() -> storage(2)).isInstanceOf(IOException.class);
        keyFiles.writeNew(Path.of("v2.dpapi"), new byte[32]);
        assertThatThrownBy(() -> storage(2)).isInstanceOf(IOException.class).hasMessageContaining("DPAPI");
        keyFiles.writeNew(Path.of("v3.dpapi"), keyFiles.read(Path.of("v1.dpapi"), 16 * 1024));
        assertThatThrownBy(() -> storage(3)).isInstanceOf(IOException.class).hasMessageContaining("DPAPI");
    }

    @Test
    void uuidCollisionDoesNotOverwriteOrDeleteExistingFile() throws IOException {
        UUID fixed = UUID.randomUUID();
        var store = new FilesystemDocumentStorage(documentFiles, new WindowsDpapiMasterKeyProvider(keyFiles, 1), () -> fixed);
        var key = store.store(42, DocumentKind.ID_PROOF, image);
        Path target = documentRoot.resolve(key.relativePath());
        byte[] original = Files.readAllBytes(target);
        assertThatThrownBy(() -> store.store(42, DocumentKind.ID_PROOF, image)).isInstanceOf(FileAlreadyExistsException.class);
        assertThat(Files.readAllBytes(target)).isEqualTo(original);
    }

    @Test
    void rejectsTraversalAndMissingOrInsecureRoot() throws IOException {
        assertThatThrownBy(() -> documentFiles.writeNew(Path.of("..", "outside"), new byte[] {1}))
                .isInstanceOf(IOException.class);
        assertThat(temp.resolve("outside")).doesNotExist();
        assertThatThrownBy(() -> new WindowsPrivateFiles(temp.resolve("missing"))).isInstanceOf(IOException.class);
        Path insecure = Files.createDirectory(temp.resolve("insecure"));
        assertThatThrownBy(() -> new WindowsPrivateFiles(insecure)).isInstanceOf(IOException.class).hasMessageContaining("ACL");
        Files.delete(documentRoot);
        assertThatThrownBy(() -> storage(1).exists(new DocumentKey(42, DocumentKind.ID_PROOF, UUID.randomUUID())))
                .isInstanceOf(IOException.class).hasMessageContaining("unavailable");
    }

    @Test
    void refusesRootsInsideGitCheckoutsOrFrontendPublic() throws IOException {
        Path checkout = Files.createDirectory(temp.resolve("checkout"));
        Files.writeString(checkout.resolve(".git"), "synthetic worktree marker");
        Path root = protectedDirectory(checkout.resolve("private"));
        assertThatThrownBy(() -> new WindowsPrivateFiles(root)).isInstanceOf(IOException.class).hasMessageContaining("Git");
        Path web = Files.createDirectories(temp.resolve(Path.of("frontend", "public")));
        Path publicRoot = protectedDirectory(web.resolve("private"));
        assertThatThrownBy(() -> new WindowsPrivateFiles(publicRoot)).isInstanceOf(IOException.class)
                .hasMessageContaining("frontend/public");
    }

    @Test
    void refusesDirectorySymlinksWhereWindowsAllowsCreatingThem() throws IOException {
        Path outside = protectedDirectory(temp.resolve("outside"));
        Path link = documentRoot.resolve("verification");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (AccessDeniedException denied) {
            assumeTrue(false, "The Windows test account cannot create symbolic links");
        } catch (FileSystemException failure) {
            String reason = failure.getReason();
            assumeTrue(reason == null || !reason.contains("privilege"),
                    "Windows symbolic link creation requires a privilege unavailable to this test account");
            throw failure;
        }
        try {
            assertThatThrownBy(() -> storage(1).store(42, DocumentKind.ID_PROOF, image))
                    .isInstanceOf(IOException.class).hasMessageContaining("reparse");
            try (var children = Files.list(outside)) {
                assertThat(children.toList()).isEmpty();
            }
        } finally {
            Files.delete(link);
        }
    }

    @Test
    void refusesJunctionsForStoreReadAndDelete() throws Exception {
        Path outside = protectedDirectory(temp.resolve("outside"));
        Path junction = documentRoot.resolve("verification");
        createJunction(junction, outside);
        try {
            var store = storage(1);
            var key = new DocumentKey(42, DocumentKind.ID_PROOF, UUID.randomUUID());
            assertThatThrownBy(() -> store.store(42, DocumentKind.ID_PROOF, image)).hasMessageContaining("reparse");
            assertThatThrownBy(() -> store.read(key)).hasMessageContaining("reparse");
            assertThatThrownBy(() -> store.delete(key)).hasMessageContaining("reparse");
            try (var children = Files.list(outside)) {
                assertThat(children.toList()).isEmpty();
            }
        } finally {
            Files.delete(junction);
        }
    }

    @Test
    void refusesHardLinkedFiles() throws IOException {
        Path outside = temp.resolve("outside-synthetic");
        Files.write(outside, new byte[] {1, 2, 3});
        var store = storage(1);
        var key = store.store(42, DocumentKind.ID_PROOF, image);
        Path target = documentRoot.resolve(key.relativePath());
        Files.delete(target);
        Files.createLink(target, outside);
        assertThatThrownBy(() -> store.read(key)).isInstanceOf(IOException.class).hasMessageContaining("hard-linked");
        assertThatThrownBy(() -> store.delete(key)).isInstanceOf(IOException.class).hasMessageContaining("hard-linked");
        assertThat(Files.readAllBytes(outside)).containsExactly(1, 2, 3);
    }

    @Test
    void failedWriteCleansUpOnlyItsOwnPartiallyCreatedBlob() throws IOException {
        var key = new DocumentKey(42, DocumentKind.ID_PROOF, UUID.randomUUID());
        var store = new FilesystemDocumentStorage(documentFiles, new WindowsDpapiMasterKeyProvider(keyFiles, 1), key::version);
        Path target = documentRoot.resolve(key.relativePath());
        FileChannel failingChannel = mock(FileChannel.class);
        when(failingChannel.write(any(ByteBuffer.class))).thenThrow(new IOException("Synthetic disk write failure"));
        try (var channels = mockStatic(FileChannel.class)) {
            channels.when(() -> FileChannel.open(eq(target), anySet(), any(FileAttribute[].class)))
                    .thenAnswer(invocation -> {
                        var attributes = (FileAttribute<?>[]) invocation.getRawArguments()[2];
                        try (var partial = Files.newByteChannel(target,
                                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), attributes)) {
                            partial.write(ByteBuffer.wrap(new byte[] {1}));
                        }
                        return failingChannel;
                    });
            assertThatThrownBy(() -> store.store(key.doctorId(), key.kind(), image))
                    .isInstanceOf(IOException.class).hasMessageContaining("Synthetic disk write failure")
                    .satisfies(failure -> assertThat(failure.getSuppressed()).isEmpty());
        }
        assertThat(target).doesNotExist();
        assertThat(store.exists(key)).isFalse();
    }

    private FilesystemDocumentStorage storage(int activeVersion) throws IOException {
        return new FilesystemDocumentStorage(documentFiles, new WindowsDpapiMasterKeyProvider(keyFiles, activeVersion),
                UUID::randomUUID);
    }

    private static void provision(WindowsPrivateFiles keyFiles, int version) throws IOException {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        try {
            byte[] blob = Crypt32Util.cryptProtectData(raw, WindowsDpapiMasterKeyProvider.purpose(version),
                    WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null, null);
            keyFiles.writeNew(Path.of("v" + version + ".dpapi"), blob);
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    static Path protectedDirectory(Path path) throws IOException {
        Files.createDirectory(path);
        var token = new WinNT.HANDLEByReference();
        assertThat(Advapi32.INSTANCE.OpenProcessToken(Kernel32.INSTANCE.GetCurrentProcess(), WinNT.TOKEN_QUERY, token)).isTrue();
        try {
            var sid = new WinNT.PSID(Advapi32Util.getTokenAccount(token.getValue()).sid);
            var acl = new WinNT.ACL(1024);
            assertThat(Advapi32.INSTANCE.InitializeAcl(acl, 1024, WinNT.ACL_REVISION)).isTrue();
            assertThat(Advapi32.INSTANCE.AddAccessAllowedAceEx(acl, WinNT.ACL_REVISION,
                    WinNT.OBJECT_INHERIT_ACE | WinNT.CONTAINER_INHERIT_ACE, WinNT.FILE_ALL_ACCESS, sid)).isTrue();
            assertThat(Advapi32.INSTANCE.SetNamedSecurityInfo(path.toString(), SE_FILE_OBJECT,
                    WinNT.DACL_SECURITY_INFORMATION | WinNT.PROTECTED_DACL_SECURITY_INFORMATION,
                    null, null, acl.getPointer(), null)).isZero();
        } finally {
            Kernel32Util.closeHandleRef(token);
        }
        return path;
    }

    private static void createJunction(Path junction, Path target) throws Exception {
        String command = "New-Item -ItemType Junction -Path '" + junction.toString().replace("'", "''")
                + "' -Target '" + target.toString().replace("'", "''") + "' -ErrorAction Stop | Out-Null";
        String script = Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_16LE));
        Path powershell = Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        var process = new ProcessBuilder(powershell.toString(), "-NoProfile", "-NonInteractive", "-EncodedCommand", script)
                .redirectErrorStream(true).start();
        try {
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).withFailMessage(new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8)).isZero();
        } finally {
            process.destroy();
        }
    }
}
