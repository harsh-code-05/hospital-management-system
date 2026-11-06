package com.harsh.hospitalmanagement.storage;

import com.harsh.hospitalmanagement.storage.VerificationImageValidator.Format;
import com.harsh.hospitalmanagement.storage.VerificationImageValidator.ValidatedImage;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

final class DocumentCipher {

    private static final int MAGIC = 0x484d5344; // HMSD
    private static final byte FORMAT_VERSION = 1;
    private static final int DATA_KEY_BYTES = 32;
    private static final int WRAPPED_KEY_BYTES = DATA_KEY_BYTES + 8;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BYTES = 16;
    private static final int HEADER_BYTES = 4 + 1 + 4 + 1 + 4 + NONCE_BYTES + WRAPPED_KEY_BYTES;
    static final int MAX_ENCRYPTED_BYTES = HEADER_BYTES + VerificationImageValidator.MAX_FILE_BYTES + TAG_BYTES;

    private final MasterKeyProvider keys;
    private final SecureRandom random = new SecureRandom();

    DocumentCipher(MasterKeyProvider keys) {
        this.keys = keys;
    }

    byte[] encrypt(DocumentKey key, ValidatedImage image) throws IOException {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        int keyVersion = keys.activeVersion();
        if (keyVersion <= 0) {
            throw new IOException("Invalid active document master-key version");
        }
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] wrappedKey;
            byte[] dataKey = new byte[DATA_KEY_BYTES];
            try {
                random.nextBytes(dataKey);
                wrappedKey = keys.wrapDataKey(keyVersion, dataKey);
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dataKey, "AES"),
                        new GCMParameterSpec(TAG_BYTES * 8, nonce));
            } finally {
                Arrays.fill(dataKey, (byte) 0);
            }
            if (wrappedKey.length != WRAPPED_KEY_BYTES) {
                throw new IOException("Invalid wrapped document data-key size");
            }
            var header = ByteBuffer.allocate(HEADER_BYTES);
            header.putInt(MAGIC).put(FORMAT_VERSION).putInt(keyVersion).put(image.format().code)
                    .putInt(image.size()).put(nonce).put(wrappedKey);
            authenticate(cipher, key, header.array());
            var blob = ByteBuffer.allocate(HEADER_BYTES + image.size() + TAG_BYTES).put(header.array());
            cipher.doFinal(image.encoded(), 0, image.size(), blob.array(), HEADER_BYTES);
            return blob.array();
        } catch (GeneralSecurityException exception) {
            throw new IOException("Unable to encrypt the verification document", exception);
        }
    }

    Decoded decrypt(DocumentKey key, byte[] blob) throws IOException {
        if (blob.length < HEADER_BYTES + TAG_BYTES || blob.length > MAX_ENCRYPTED_BYTES) {
            throw invalidDocument();
        }
        var header = ByteBuffer.wrap(blob);
        if (header.getInt() != MAGIC || header.get() != FORMAT_VERSION) {
            throw invalidDocument();
        }
        int keyVersion = header.getInt();
        Format format = Format.fromCode(header.get());
        int size = header.getInt();
        if (keyVersion <= 0 || format == null || size <= 0 || size != blob.length - HEADER_BYTES - TAG_BYTES) {
            throw invalidDocument();
        }
        byte[] nonce = new byte[NONCE_BYTES];
        byte[] wrappedKey = new byte[WRAPPED_KEY_BYTES];
        header.get(nonce).get(wrappedKey);
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] dataKey = keys.unwrapDataKey(keyVersion, wrappedKey);
            try {
                if (dataKey.length != DATA_KEY_BYTES) {
                    throw invalidDocument();
                }
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dataKey, "AES"),
                        new GCMParameterSpec(TAG_BYTES * 8, nonce));
            } finally {
                Arrays.fill(dataKey, (byte) 0);
            }
            authenticate(cipher, key, blob);
            // One-shot authenticated decryption. No plaintext reaches the caller before tag verification.
            return new Decoded(format, cipher.doFinal(blob, HEADER_BYTES, blob.length - HEADER_BYTES));
        } catch (GeneralSecurityException exception) {
            throw invalidDocument();
        }
    }

    private static void authenticate(Cipher cipher, DocumentKey key, byte[] header) {
        cipher.updateAAD(header, 0, HEADER_BYTES);
        cipher.updateAAD(key.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private static IOException invalidDocument() {
        return new IOException("Verification document failed integrity checks");
    }

    record Decoded(Format format, byte[] bytes) {
    }
}
