# Private verification document storage for Windows development

Phase 1 is for synthetic images. The adapter has no HTTP routes, database catalog, or retention worker. Keep document storage disabled for the running backend.

## Configuration

| Property | Value |
|---|---|
| `hms.document-storage.enabled` | Defaults to disabled. Explicitly set `true` only in an isolated development instance. |
| `hms.document-storage.root` | Defaults to `C:/ProgramData/HMS/private-documents`. The directory must already exist. |
| `hms.document-storage.key-directory` | Absolute directory on a **different** local fixed NTFS volume. |
| `hms.document-storage.master-key-version` | Positive integer matching the active DPAPI file, such as version `1` for `v1.dpapi`. |

Provision both directories with protected NTFS DACLs before enabling storage. Allow only the Windows identity that runs HMS, optionally with Local System and built-in Administrators. Use canonical paths outside Git checkouts and web roots. HMS verifies the directories and active key at startup and gives new subdirectories and files restrictive ACLs at creation. Enabling storage with missing, shared, or unsafe configuration fails startup.

The storage service uses the platform-neutral `PrivateFiles` boundary. Windows currently supplies the NTFS adapter and the DPAPI master-key provider. Oracle Linux deployment with storage enabled remains unsupported until both a secure POSIX filesystem adapter and a Linux master-key provider exist.

CI still needs a successful enabled-storage startup test with two genuinely separate NTFS volumes. Existing tests cover shared-volume rejection and synthetic DPAPI operations. A `subst` drive letter maps an existing directory on its original volume.

## Provision a master key

Run this provisioning example under the intended Windows HMS identity in PowerShell on Windows. Replace the placeholder key directory with the already provisioned private directory on the separate volume. The example writes a new DPAPI blob exclusively and clears the source key array after use.

```powershell
$hmsKeyDirectory = 'D:\HMS\private-keys'
$hmsKeyVersion = 1
$hmsKeyPath = Join-Path $hmsKeyDirectory "v$hmsKeyVersion.dpapi"
$hmsEntropy = [Text.Encoding]::UTF8.GetBytes("HMS-private-documents:v$hmsKeyVersion")
$hmsKey = New-Object byte[] 32
$hmsRandom = [Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $hmsRandom.GetBytes($hmsKey)
    $hmsProtected = [Security.Cryptography.ProtectedData]::Protect(
        $hmsKey, $hmsEntropy, [Security.Cryptography.DataProtectionScope]::CurrentUser
    )
    $hmsFile = [IO.File]::Open($hmsKeyPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try {
        $hmsFile.Write($hmsProtected, 0, $hmsProtected.Length)
        $hmsFile.Flush($true)
    } finally {
        $hmsFile.Dispose()
    }
} finally {
    [Array]::Clear($hmsKey, 0, $hmsKey.Length)
    $hmsRandom.Dispose()
}
```

Use only blobs provisioned with `CurrentUser` protection. DPAPI normally needs the originating Windows identity and machine profile. A blob copy alone does not provide portable recovery. To rotate, provision a new version, change the active version in the isolated instance, and keep prior versions while files still need them.

## Key memory and heap dumps

The adapter unlocks master keys for individual operations without caching plaintext. It clears temporary plaintext key arrays once their ciphers have been initialized. `SecretKeySpec`, the JCA provider, and the JVM can retain internal copies, so this is best-effort cleanup rather than a guarantee of memory erasure.

Keep automatic JVM heap dumps disabled, including `-XX:-HeapDumpOnOutOfMemoryError`, and restrict JVM diagnostic attach and management access to trusted operators. Avoid manual heap dumps, debugger captures, and crash dumps from instances with storage enabled. If an incident requires a capture, handle it as sensitive evidence that may contain plaintext keys or documents. Restrict access, encrypt it, and remove it under the applicable retention policy.

## Validation and lifecycle boundaries

The validator checks the actual input, bounds raw request bodies to 11 MiB and individual images to 5 MiB, and bounds decoding to 12 megapixels. Per JVM, one validation can run and up to three can wait for approximately five seconds. Excess callers fail before reading image bytes. Interruption while waiting preserves the thread's interrupt status. The timeout bounds admission to decoding and does not bound the time spent reading or decoding an admitted image.

JPEG and PNG are decoded and rewritten in memory. JPEG uses an explicit re-encoding quality of `0.92`. PNG keeps its existing encoding behavior. ImageIO runs inside the JVM. Isolate the decoder before handling real evidence.

The storage adapter returns only authenticated bytes. A file may remain unpublished if the process loses power during a write. Live reference checks, interrupted-write reconciliation, portable key escrow, backup rehearsal, and authorization for upload and download belong to later phases.

## Required for Phase 2 HTTP integration

Set these limits when the multipart endpoint is introduced. They are requirements for that phase and are not active application settings in Phase 1.

```properties
spring.servlet.multipart.max-file-size=5MB
spring.servlet.multipart.max-request-size=11MB
```

- Enforce the request limit at the HTTP ingress and the server's multipart boundary. Count actual bytes, including multipart overhead and requests without a trustworthy `Content-Length`.
- Bound multipart parsing by bytes, part count, header sizes, and time. Limit concurrent requests before parsing so the decoder semaphore cannot become the only resource control.
- Reject oversized HTTP requests with `413 Payload Too Large` before expensive decoding. Verify that rejected requests reach neither image validation nor storage.
