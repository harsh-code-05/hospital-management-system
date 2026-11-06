package com.harsh.hospitalmanagement.storage;

import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.Win32Exception;
import com.sun.jna.platform.win32.WinCrypt;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/** Reads versioned DPAPI blobs provisioned in CurrentUser scope by the service account. */
final class WindowsDpapiMasterKeyProvider implements MasterKeyProvider {

    private final PrivateFiles files;
    private final int activeVersion;

    WindowsDpapiMasterKeyProvider(PrivateFiles files, int activeVersion) throws IOException {
        this.files = files;
        this.activeVersion = activeVersion;
        Arrays.fill(loadMasterKey(activeVersion), (byte) 0);
    }

    @Override
    public int activeVersion() {
        return activeVersion;
    }

    @Override
    public byte[] wrapDataKey(int version, byte[] dataKey) throws IOException, GeneralSecurityException {
        return applyKeyWrap(Cipher.ENCRYPT_MODE, version, dataKey);
    }

    @Override
    public byte[] unwrapDataKey(int version, byte[] wrappedKey) throws IOException, GeneralSecurityException {
        return applyKeyWrap(Cipher.DECRYPT_MODE, version, wrappedKey);
    }

    private byte[] applyKeyWrap(int mode, int version, byte[] input) throws IOException, GeneralSecurityException {
        // AES/KW/NoPadding is the same RFC 3394 format as AESWrap. Operating on bytes
        // avoids building another SecretKeySpec just to wrap or unwrap the data key.
        var cipher = Cipher.getInstance("AES/KW/NoPadding");
        byte[] secret = loadMasterKey(version);
        try {
            cipher.init(mode, new SecretKeySpec(secret, "AES"));
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
        return cipher.doFinal(input);
    }

    private byte[] loadMasterKey(int version) throws IOException {
        if (version <= 0) {
            throw new IOException("Invalid document master-key version");
        }
        byte[] blob = files.read(Path.of("v" + version + ".dpapi"), 16 * 1024);
        byte[] secret;
        try {
            secret = Crypt32Util.cryptUnprotectData(blob, purpose(version), WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null);
        } catch (Win32Exception failure) {
            throw new IOException("Windows DPAPI could not unlock the document master key");
        }
        if (secret.length != 32) {
            Arrays.fill(secret, (byte) 0);
            throw new IOException("Document master keys must contain exactly 256 bits");
        }
        return secret;
    }

    static byte[] purpose(int version) {
        return ("HMS-private-documents:v" + version).getBytes(StandardCharsets.UTF_8);
    }
}
