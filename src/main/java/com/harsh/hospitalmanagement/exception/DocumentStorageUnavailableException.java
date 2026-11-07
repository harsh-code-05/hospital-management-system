package com.harsh.hospitalmanagement.exception;

public class DocumentStorageUnavailableException extends RuntimeException {

    public DocumentStorageUnavailableException(String message) {
        super(message);
    }

    public DocumentStorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
