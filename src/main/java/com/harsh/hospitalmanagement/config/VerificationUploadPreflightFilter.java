package com.harsh.hospitalmanagement.config;

import com.harsh.hospitalmanagement.storage.DocumentStorage;
import com.harsh.hospitalmanagement.storage.MultipartStagingManager;
import com.harsh.hospitalmanagement.storage.VerificationImageValidator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class VerificationUploadPreflightFilter extends OncePerRequestFilter {

    private static final Pattern UPLOAD_PATH = Pattern.compile(
            "^/api/doctors/[1-9][0-9]*/verification/documents/(?:id-proof|qualification)$",
            Pattern.CASE_INSENSITIVE
    );

    private final DocumentStorage documentStorage;
    private final MultipartStagingManager stagingManager;

    public VerificationUploadPreflightFilter(
            @Autowired(required = false) DocumentStorage documentStorage,
            @Autowired(required = false) MultipartStagingManager stagingManager
    ) {
        this.documentStorage = documentStorage;
        this.stagingManager = stagingManager;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if ("POST".equalsIgnoreCase(request.getMethod()) && UPLOAD_PATH.matcher(request.getRequestURI()).matches()) {
            long contentLength = request.getContentLengthLong();
            if (contentLength > VerificationImageValidator.MAX_REQUEST_BYTES) {
                response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write(String.format(
                        "{\"timestamp\":\"%s\",\"status\":413,\"message\":\"Uploaded file exceeds the maximum allowed size\"}",
                        LocalDateTime.now()
                ));
                return;
            }

            if (documentStorage == null) {
                sendUnavailable(response, "Document storage service is temporarily unavailable");
                return;
            }
            if (stagingManager != null) {
                try {
                    stagingManager.validateStagingDirectory();
                } catch (Exception e) {
                    sendUnavailable(response, "Document storage service is temporarily unavailable");
                    return;
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    private void sendUnavailable(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(String.format(
                "{\"timestamp\":\"%s\",\"status\":503,\"message\":\"%s\"}",
                LocalDateTime.now(),
                message
        ));
    }
}
