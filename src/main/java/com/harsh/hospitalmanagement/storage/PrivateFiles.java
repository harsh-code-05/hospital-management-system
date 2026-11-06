package com.harsh.hospitalmanagement.storage;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Filesystem operations scoped to a provisioned private root. Implementations must confine
 * all operations to that root and fail closed on unsafe paths or permissions.
 * Paths are internal and relative.
 */
interface PrivateFiles {

    /** Creates a new file exclusively, preserving any existing file at the address. */
    void writeNew(Path relative, byte[] bytes) throws IOException;

    /** Returns bounded content, rejecting empty or oversized files. */
    byte[] read(Path relative, int maxBytes) throws IOException;

    /** Returns false for a missing file and throws on unsafe or unavailable storage. */
    boolean exists(Path relative) throws IOException;

    /** Deletes the addressed file. Returns false if it is missing. */
    boolean delete(Path relative) throws IOException;
}
