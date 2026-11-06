package com.harsh.hospitalmanagement.storage;

import java.io.IOException;
import java.security.GeneralSecurityException;

public interface MasterKeyProvider {

    int activeVersion();

    /** Wraps the caller-owned data key without retaining it after the operation. */
    byte[] wrapDataKey(int version, byte[] dataKey) throws IOException, GeneralSecurityException;

    /** Returns a fresh plaintext data-key array that the caller must clear after use. */
    byte[] unwrapDataKey(int version, byte[] wrappedKey) throws IOException, GeneralSecurityException;
}
