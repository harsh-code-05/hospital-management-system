package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.dto.DoctorVerificationDocumentResponse;
import com.harsh.hospitalmanagement.entity.DoctorVerificationDocument;
import com.harsh.hospitalmanagement.enums.VerificationDocumentKind;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.exception.DocumentStorageUnavailableException;
import com.harsh.hospitalmanagement.service.DoctorVerificationDocumentService;
import com.harsh.hospitalmanagement.storage.DocumentStorage;
import com.harsh.hospitalmanagement.storage.VerificationImageValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/doctors/{doctorId}/verification/documents")
public class DoctorVerificationDocumentController {

    private final DoctorVerificationDocumentService documentService;
    private final VerificationImageValidator imageValidator;

    public DoctorVerificationDocumentController(
            DoctorVerificationDocumentService documentService,
            @Autowired(required = false) VerificationImageValidator imageValidator
    ) {
        this.documentService = documentService;
        this.imageValidator = imageValidator != null ? imageValidator : new VerificationImageValidator();
    }

    @PostMapping(value = "/{kind}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DoctorVerificationDocumentResponse> uploadDocument(
            @PathVariable long doctorId,
            @PathVariable String kind,
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            org.springframework.web.multipart.MultipartHttpServletRequest request,
            Authentication authentication
    ) {
        long validDoctorId = positiveId(doctorId);
        VerificationDocumentKind docKind = parseKind(kind);

        if (request != null) {
            long totalSize = request.getMultiFileMap().values().stream()
                    .flatMap(List::stream)
                    .mapToLong(MultipartFile::getSize)
                    .sum();
            if (totalSize > VerificationImageValidator.MAX_REQUEST_BYTES) {
                throw new MaxUploadSizeExceededException(VerificationImageValidator.MAX_REQUEST_BYTES);
            }
        }

        if (file != null && file.getSize() > VerificationImageValidator.MAX_FILE_BYTES) {
            throw new MaxUploadSizeExceededException(VerificationImageValidator.MAX_FILE_BYTES);
        }

        if (!documentService.isStorageAvailable()) {
            throw new DocumentStorageUnavailableException("Document storage service is temporarily unavailable");
        }

        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Verification document file must not be empty");
        }

        String contentType = file.getContentType();
        if (contentType == null || (!contentType.equalsIgnoreCase("image/png") && !contentType.equalsIgnoreCase("image/jpeg"))) {
            throw new BadRequestException("Only PNG and JPEG images are supported for verification documents");
        }

        VerificationImageValidator.ValidatedImage validatedImage;
        try (InputStream input = file.getInputStream()) {
            validatedImage = imageValidator.validate(contentType, input);
        } catch (IOException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Invalid image";
            if (msg.contains("5 MiB") || msg.contains("11 MiB")) {
                throw new MaxUploadSizeExceededException(5242880L);
            }
            throw new BadRequestException("Invalid verification document image: " + msg);
        }

        DoctorVerificationDocument document = documentService.attachDocument(
                validDoctorId, docKind, validatedImage, authentication, correlationId);

        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(DoctorVerificationDocumentResponse.from(document));
    }

    @DeleteMapping("/{kind}")
    public ResponseEntity<Void> detachDocument(
            @PathVariable long doctorId,
            @PathVariable String kind,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            Authentication authentication
    ) {
        long validDoctorId = positiveId(doctorId);
        VerificationDocumentKind docKind = parseKind(kind);

        documentService.detachDocument(validDoctorId, docKind, authentication, correlationId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{kind}")
    public ResponseEntity<byte[]> readDocument(
            @PathVariable long doctorId,
            @PathVariable String kind,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            Authentication authentication
    ) {
        long validDoctorId = positiveId(doctorId);
        VerificationDocumentKind docKind = parseKind(kind);

        DocumentStorage.ReadDocument readDoc = documentService.readDocument(
                validDoctorId, docKind, authentication, correlationId);

        String ext = readDoc.contentType().contains("png") ? "png" : "jpg";
        String filename = String.format("doctor-%d-%s.%s", validDoctorId, docKind.pathSegment(), ext);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(readDoc.contentType()));
        headers.setContentLength(readDoc.bytes().length);
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, private, max-age=0");
        headers.setPragma("no-cache");

        return ResponseEntity.ok().headers(headers).body(readDoc.bytes());
    }

    @GetMapping
    public ResponseEntity<List<DoctorVerificationDocumentResponse>> getActiveDocuments(
            @PathVariable long doctorId,
            Authentication authentication
    ) {
        long validDoctorId = positiveId(doctorId);
        List<DoctorVerificationDocumentResponse> responses = documentService
                .getActiveDocuments(validDoctorId, authentication).stream()
                .map(DoctorVerificationDocumentResponse::from)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{kind}/history")
    public ResponseEntity<List<DoctorVerificationDocumentResponse>> getDocumentHistory(
            @PathVariable long doctorId,
            @PathVariable String kind,
            Authentication authentication
    ) {
        long validDoctorId = positiveId(doctorId);
        VerificationDocumentKind docKind = parseKind(kind);

        List<DoctorVerificationDocumentResponse> responses = documentService
                .getDocumentHistory(validDoctorId, docKind, authentication).stream()
                .map(DoctorVerificationDocumentResponse::from)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, Object>> invalidDoctorId() {
        return ResponseEntity.badRequest().body(Map.of("status", 400, "message", "Doctor ID must be a positive integer"));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<Map<String, Object>> missingPart(MissingServletRequestPartException e) {
        return ResponseEntity.badRequest().body(Map.of("status", 400, "message", e.getMessage()));
    }

    private static long positiveId(long doctorId) {
        if (doctorId <= 0) {
            throw new BadRequestException("Doctor ID must be a positive integer");
        }
        return doctorId;
    }

    private static VerificationDocumentKind parseKind(String kind) {
        if (kind == null || kind.isBlank()) {
            throw new BadRequestException("Verification document kind is required");
        }
        String normalized = kind.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (VerificationDocumentKind docKind : VerificationDocumentKind.values()) {
            if (docKind.pathSegment().equals(normalized) || docKind.name().equalsIgnoreCase(kind.trim())) {
                return docKind;
            }
        }
        throw new BadRequestException("Invalid verification document kind: " + kind);
    }
}
