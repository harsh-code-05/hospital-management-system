package com.harsh.hospitalmanagement.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "hms.document-storage", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(DocumentStorageConfiguration.Settings.class)
class DocumentStorageConfiguration {

    @Bean
    DocumentStorage documentStorage(Settings settings) throws IOException {
        if (settings.keyDirectory() == null || settings.masterKeyVersion() <= 0) {
            throw new IOException("Private storage requires a DPAPI key directory and a positive master-key version");
        }
        var documents = new WindowsPrivateFiles(settings.root());
        var keyFiles = new WindowsPrivateFiles(settings.keyDirectory());
        if (documents.volumeId().equalsIgnoreCase(keyFiles.volumeId())) {
            throw new IOException("The DPAPI master-key directory must be on a separate NTFS volume");
        }
        return new FilesystemDocumentStorage(documents,
                new WindowsDpapiMasterKeyProvider(keyFiles, settings.masterKeyVersion()), UUID::randomUUID);
    }

    @Bean
    VerificationImageValidator verificationImageValidator() {
        return new VerificationImageValidator();
    }

    @ConfigurationProperties("hms.document-storage")
    record Settings(@DefaultValue("C:/ProgramData/HMS/private-documents") Path root,
                    Path keyDirectory, int masterKeyVersion) {
    }
}
