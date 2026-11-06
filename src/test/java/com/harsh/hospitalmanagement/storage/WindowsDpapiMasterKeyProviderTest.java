package com.harsh.hospitalmanagement.storage;

import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.WinCrypt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnabledOnOs(OS.WINDOWS)
class WindowsDpapiMasterKeyProviderTest {

    @Test
    void wrapsAndUnwrapsTheSameKeyFormatAsPhaseOne() throws Exception {
        var random = new SecureRandom();
        byte[] masterKey = new byte[32];
        byte[] dataKey = new byte[32];
        try {
            random.nextBytes(masterKey);
            random.nextBytes(dataKey);
            byte[] protectedKey = Crypt32Util.cryptProtectData(masterKey, WindowsDpapiMasterKeyProvider.purpose(1),
                    WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null, null);
            var files = mock(PrivateFiles.class);
            when(files.read(Path.of("v1.dpapi"), 16 * 1024)).thenReturn(protectedKey);
            var provider = new WindowsDpapiMasterKeyProvider(files, 1);

            var legacy = Cipher.getInstance("AESWrap");
            legacy.init(Cipher.WRAP_MODE, new SecretKeySpec(masterKey, "AES"));
            byte[] legacyWrapped = legacy.wrap(new SecretKeySpec(dataKey, "AES"));
            assertThat(provider.wrapDataKey(1, dataKey)).isEqualTo(legacyWrapped);

            byte[] unwrapped = provider.unwrapDataKey(1, legacyWrapped);
            try {
                assertThat(unwrapped).isEqualTo(dataKey);
            } finally {
                Arrays.fill(unwrapped, (byte) 0);
            }
        } finally {
            Arrays.fill(dataKey, (byte) 0);
            Arrays.fill(masterKey, (byte) 0);
        }
    }
}
