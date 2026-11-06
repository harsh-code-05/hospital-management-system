package com.harsh.hospitalmanagement.storage;

import com.harsh.hospitalmanagement.service.DoctorVerificationService.DocumentKind;
import com.harsh.hospitalmanagement.storage.VerificationImageValidator.ValidatedImage;

import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.Supplier;

final class FilesystemDocumentStorage implements DocumentStorage {

    private final PrivateFiles files;
    private final DocumentCipher cipher;
    private final Supplier<UUID> versions;

    FilesystemDocumentStorage(PrivateFiles files, MasterKeyProvider keys, Supplier<UUID> versions) {
        this.files = files;
        cipher = new DocumentCipher(keys);
        this.versions = versions;
    }

    @Override
    public DocumentKey store(long doctorId, DocumentKind kind, ValidatedImage image) throws IOException {
        var key = new DocumentKey(doctorId, kind, versions.get());
        files.writeNew(key.relativePath(), cipher.encrypt(key, image));
        return key;
    }

    @Override
    public ReadDocument read(DocumentKey key) throws IOException {
        var document = decrypt(key);
        var format = document.format();
        return new ReadDocument(format.mime, key.kind().pathSegment() + "." + format.extension, document.bytes());
    }

    @Override
    public void verifyIntegrity(DocumentKey key) throws IOException {
        Arrays.fill(decrypt(key).bytes(), (byte) 0);
    }

    @Override
    public boolean exists(DocumentKey key) throws IOException {
        return files.exists(key.relativePath());
    }

    @Override
    public boolean delete(DocumentKey key) throws IOException {
        return files.delete(key.relativePath());
    }

    private DocumentCipher.Decoded decrypt(DocumentKey key) throws IOException {
        return cipher.decrypt(key, files.read(key.relativePath(), DocumentCipher.MAX_ENCRYPTED_BYTES));
    }
}
