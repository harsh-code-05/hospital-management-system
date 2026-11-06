package com.harsh.hospitalmanagement.storage;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.platform.win32.Win32Exception;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.PointerByReference;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.sun.jna.platform.win32.AccCtrl.SE_OBJECT_TYPE.SE_FILE_OBJECT;
import static com.sun.jna.platform.win32.WinError.ERROR_FILE_NOT_FOUND;
import static com.sun.jna.platform.win32.WinError.ERROR_PATH_NOT_FOUND;

/** Operations on an existing, provisioned local NTFS directory. */
final class WindowsPrivateFiles implements PrivateFiles {

    // Win32 FILE_INFO_BY_HANDLE_CLASS values.
    private static final int STANDARD_INFO = 1;
    private static final int DISPOSITION_INFO = 4;
    private static final int ATTRIBUTE_TAG_INFO = 9;

    private final Path root;
    private final String volumeId;
    private final Set<String> trustedSids;
    private final FileAttribute<List<AclEntry>> privateAcl;

    WindowsPrivateFiles(Path root) throws IOException {
        if (!Platform.isWindows() || root == null || !root.isAbsolute() || root.getNameCount() == 0
                || !root.getRoot().toString().matches("[A-Za-z]:\\\\") || !root.equals(root.normalize())) {
            throw new IOException("Private storage requires an absolute local Windows directory");
        }
        for (Path component : root) {
            if (component.toString().endsWith(".") || component.toString().endsWith(" ")) {
                throw new IOException("Private storage directory must use canonical path components");
            }
        }
        this.root = root;
        var account = processAccount();
        trustedSids = new HashSet<>(List.of(account.sidString, "S-1-5-18", "S-1-5-32-544"));
        var principal = root.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(account.fqn);
        var entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(principal)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
        privateAcl = new FileAttribute<>() {
            @Override public String name() { return "acl:acl"; }
            @Override public List<AclEntry> value() { return List.of(entry); }
        };
        volumeId = volumeId(root);
        try (var ignored = pinDirectories(root, false)) {
            if (!root.equals(root.toRealPath(LinkOption.NOFOLLOW_LINKS))
                    || !"NTFS".equalsIgnoreCase(Files.getFileStore(root).type())) {
                throw new IOException("Private storage requires a canonical NTFS directory");
            }
            requireOutsideProject(root);
        }
    }

    String volumeId() {
        return volumeId;
    }

    @Override
    public void writeNew(Path relative, byte[] bytes) throws IOException {
        Path target = resolve(relative);
        try (var ignored = pinDirectories(target.getParent(), true)) {
            boolean created = false;
            try (var file = FileChannel.open(target,
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS), privateAcl)) {
                created = true;
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    if (file.write(buffer) == 0) {
                        throw new IOException("Private storage write did not make progress");
                    }
                }
                file.force(true);
            } catch (IOException | RuntimeException failure) {
                if (created) {
                    try {
                        deletePinned(target);
                    } catch (IOException | RuntimeException cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                throw failure;
            }
        }
    }

    @Override
    public byte[] read(Path relative, int maxBytes) throws IOException {
        Path target = resolve(relative);
        try (var ignored = pinDirectories(target.getParent(), false);
             var file = pin(target, false, true, 0);
             var input = Files.newInputStream(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(maxBytes + 1);
            if (bytes.length == 0 || bytes.length > maxBytes) {
                throw new IOException("Private file has an invalid size");
            }
            return bytes;
        }
    }

    @Override
    public boolean exists(Path relative) throws IOException {
        Path target = resolve(relative);
        try (var ignored = pinDirectories(target.getParent(), false);
             var file = pin(target, false, true, 0)) {
            return true;
        } catch (NoSuchFileException missing) {
            return false;
        }
    }

    @Override
    public boolean delete(Path relative) throws IOException {
        Path target = resolve(relative);
        try (var ignored = pinDirectories(target.getParent(), false)) {
            deletePinned(target);
            return true;
        } catch (NoSuchFileException missing) {
            return false;
        }
    }

    private void deletePinned(Path target) throws IOException {
        try (var file = pin(target, false, true, WinNT.DELETE)) {
            var disposition = new WinBase.FILE_DISPOSITION_INFO(true);
            disposition.write();
            if (!Kernel32.INSTANCE.SetFileInformationByHandle(file.handle, DISPOSITION_INFO,
                    disposition.getPointer(), new DWORD(disposition.size()))) {
                throw winFailure("Private file could not be deleted", Kernel32.INSTANCE.GetLastError());
            }
        }
    }

    private Path resolve(Path relative) throws IOException {
        Path resolved = root.resolve(relative).normalize();
        if (relative.isAbsolute() || !relative.equals(relative.normalize()) || resolved.equals(root)
                || !resolved.startsWith(root)) {
            throw new IOException("Invalid private storage address");
        }
        return resolved;
    }

    private Pins pinDirectories(Path directory, boolean create) throws IOException {
        if (!volumeId.equalsIgnoreCase(volumeId(root))) {
            throw new IOException("Private storage volume is unavailable");
        }
        var pins = new Pins();
        Path current = directory.getRoot();
        try {
            pins.add(pin(current, true, false, 0));
            for (Path component : directory) {
                current = current.resolve(component);
                boolean inside = current.startsWith(root);
                if (create && inside && !current.equals(root)) {
                    try {
                        Files.createDirectory(current, privateAcl);
                    } catch (FileAlreadyExistsException existing) {
                        // The handle and ACL checks below also apply to existing entries.
                    }
                }
                pins.add(pin(current, true, inside, 0));
            }
            return pins;
        } catch (IOException | RuntimeException failure) {
            try {
                pins.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            if (failure instanceof NoSuchFileException && (!current.startsWith(root) || current.equals(root))) {
                throw new IOException("Private storage directory is unavailable", failure);
            }
            throw failure;
        }
    }

    private Pin pin(Path path, boolean directory, boolean checkAcl, int access) throws IOException {
        // Pin each ancestor without write/delete sharing so it cannot be renamed or turned into a junction.
        var handle = Kernel32.INSTANCE.CreateFile(path.toString(),
                WinNT.FILE_READ_ATTRIBUTES | (checkAcl ? WinNT.READ_CONTROL : 0) | access,
                WinNT.FILE_SHARE_READ, null, WinNT.OPEN_EXISTING,
                WinNT.FILE_FLAG_BACKUP_SEMANTICS | WinNT.FILE_FLAG_OPEN_REPARSE_POINT, null);
        if (handle == null || WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
            int error = Kernel32.INSTANCE.GetLastError();
            if (error == ERROR_FILE_NOT_FOUND || error == ERROR_PATH_NOT_FOUND) {
                throw new NoSuchFileException("Private file or directory is missing");
            }
            throw winFailure("Private file or directory is unavailable", error);
        }
        var pin = new Pin(handle);
        try {
            var tag = new WinBase.FILE_ATTRIBUTE_TAG_INFO();
            query(handle, ATTRIBUTE_TAG_INFO, tag);
            int attributes = tag.FileAttributes;
            if ((attributes & WinNT.FILE_ATTRIBUTE_REPARSE_POINT) != 0
                    || ((attributes & WinNT.FILE_ATTRIBUTE_DIRECTORY) != 0) != directory
                    || Kernel32.INSTANCE.GetFileType(handle) != WinNT.FILE_TYPE_DISK) {
                throw new IOException("Private storage refuses reparse points or unexpected file types");
            }
            if (!directory) {
                var standard = new WinBase.FILE_STANDARD_INFO();
                query(handle, STANDARD_INFO, standard);
                if (standard.NumberOfLinks != 1) {
                    throw new IOException("Private storage refuses hard-linked files");
                }
            }
            if (checkAcl) {
                requirePrivateAcl(handle, path.equals(root));
            }
            return pin;
        } catch (IOException | RuntimeException failure) {
            try {
                pin.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private void requirePrivateAcl(WinNT.HANDLE handle, boolean requireProtected) throws IOException {
        var descriptor = new PointerByReference();
        int error = Advapi32.INSTANCE.GetSecurityInfo(handle, SE_FILE_OBJECT,
                WinNT.OWNER_SECURITY_INFORMATION | WinNT.DACL_SECURITY_INFORMATION,
                null, null, null, null, descriptor);
        if (error != 0) {
            throw winFailure("Unable to read private storage permissions", error);
        }
        try {
            var security = new WinNT.SECURITY_DESCRIPTOR_RELATIVE(descriptor.getValue());
            var acl = security.getDiscretionaryACL();
            if (!trustedSids.contains(security.getOwner().getSidString()) || acl == null || acl.AceCount == 0
                    || (requireProtected && (security.Control & WinNT.SE_DACL_PROTECTED) == 0)) {
                throw new IOException("Private storage requires a protected ACL and a trusted owner");
            }
            for (var ace : acl.getACEs()) {
                if (ace instanceof WinNT.ACCESS_DENIED_ACE) {
                    continue;
                }
                if (!(ace instanceof WinNT.ACCESS_ALLOWED_ACE allowed) || !trustedSids.contains(allowed.getSidString())) {
                    throw new IOException("Private storage ACL grants untrusted access");
                }
            }
        } finally {
            Kernel32Util.freeLocalMemory(descriptor.getValue());
        }
    }

    private static void query(WinNT.HANDLE handle, int informationClass, com.sun.jna.Structure info) throws IOException {
        if (!Kernel32.INSTANCE.GetFileInformationByHandleEx(handle, informationClass, info.getPointer(), new DWORD(info.size()))) {
            throw winFailure("Unable to inspect a private storage handle", Kernel32.INSTANCE.GetLastError());
        }
        info.read();
    }

    private static String volumeId(Path path) throws IOException {
        String drive = path.getRoot().toString();
        char[] volume = new char[64];
        if (Kernel32.INSTANCE.GetDriveType(drive) != WinBase.DRIVE_FIXED
                || !Kernel32.INSTANCE.GetVolumeNameForVolumeMountPoint(drive, volume, volume.length)) {
            throw new IOException("Private storage requires a mounted local fixed volume");
        }
        return Native.toString(volume);
    }

    private static Advapi32Util.Account processAccount() throws IOException {
        var token = new WinNT.HANDLEByReference();
        if (!Advapi32.INSTANCE.OpenProcessToken(Kernel32.INSTANCE.GetCurrentProcess(), WinNT.TOKEN_QUERY, token)) {
            throw winFailure("Unable to identify the Windows service account", Kernel32.INSTANCE.GetLastError());
        }
        try (var ignored = new Pin(token.getValue())) {
            return Advapi32Util.getTokenAccount(token.getValue());
        } catch (Win32Exception failure) {
            throw winFailure("Unable to identify the Windows service account", failure.getErrorCode());
        }
    }

    private static void requireOutsideProject(Path directory) throws IOException {
        for (Path current = directory; current != null; current = current.getParent()) {
            try {
                Files.readAttributes(current.resolve(".git"), java.nio.file.attribute.BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                throw new IOException("Private storage cannot be inside a Git checkout");
            } catch (NoSuchFileException missing) {
                // Check every ancestor, including Git worktrees whose .git is a file.
            }
            if (current.endsWith(Path.of("frontend", "public"))) {
                throw new IOException("Private storage cannot be inside frontend/public");
            }
        }
    }

    private static IOException winFailure(String message, int error) {
        return new IOException(message + " (Windows error " + error + ")");
    }

    private record Pin(WinNT.HANDLE handle) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            if (!Kernel32.INSTANCE.CloseHandle(handle)) {
                throw winFailure("Unable to release a private storage handle", Kernel32.INSTANCE.GetLastError());
            }
        }
    }

    private static final class Pins implements AutoCloseable {
        private final ArrayDeque<Pin> handles = new ArrayDeque<>();

        void add(Pin pin) {
            handles.push(pin);
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            while (!handles.isEmpty()) {
                try {
                    handles.pop().close();
                } catch (IOException closeFailure) {
                    if (failure == null) {
                        failure = closeFailure;
                    } else {
                        failure.addSuppressed(closeFailure);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
