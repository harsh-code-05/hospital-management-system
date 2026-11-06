package com.harsh.hospitalmanagement.storage;

import com.harsh.hospitalmanagement.service.DoctorVerificationService.DocumentKind;
import com.harsh.hospitalmanagement.storage.VerificationImageValidator.ValidatedImage;

import java.io.IOException;

/** Internal adapter. Callers must authorize access before resolving or using a document key. */
public interface DocumentStorage {

    DocumentKey store(long doctorId, DocumentKind kind, ValidatedImage image) throws IOException;

    ReadDocument read(DocumentKey key) throws IOException;

    /** Throws if the file is missing or cannot be authenticated with its recorded key version. */
    void verifyIntegrity(DocumentKey key) throws IOException;

    /** Checks for a safely addressable file. Use verifyIntegrity to authenticate its contents. */
    boolean exists(DocumentKey key) throws IOException;

    /** Returns false for an absent document. Reference and retention checks belong to the caller. */
    boolean delete(DocumentKey key) throws IOException;

    record ReadDocument(String contentType, String downloadFilename, byte[] bytes) {
    }
}
