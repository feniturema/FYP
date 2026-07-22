package my.edu.ukm.ftsm.ecommerce.controller;

import my.edu.ukm.ftsm.ecommerce.exception.BusinessException;
import my.edu.ukm.ftsm.ecommerce.config.UploadPathResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * Handles image uploads for product/item listings.
 * Files are stored under the configured upload directory and served
 * statically by Spring via ResourceHandlerRegistry (see WebMvcConfig).
 */
@RestController
@RequestMapping("/api/upload")
public class UploadController {

    private static final long MAX_SIZE_BYTES = 5 * 1024 * 1024; // 5 MB
    private static final java.util.Set<String> ALLOWED_TYPES = java.util.Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif");

    private final Path uploadDir;

    public UploadController(@Value("${app.upload.dir:uploads}") String uploadDir) {
        this.uploadDir = UploadPathResolver.resolve(uploadDir);
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> upload(@RequestParam("file") MultipartFile file)
            throws IOException {
        if (file.isEmpty()) {
            throw new BusinessException("No file provided.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new BusinessException("Only JPEG, PNG, WebP and GIF images are allowed.");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new BusinessException("File size must not exceed 5 MB.");
        }
        String ext = contentType.substring(contentType.lastIndexOf('/') + 1)
                .replace("jpeg", "jpg");
        String filename = UUID.randomUUID() + "." + ext;

        Files.createDirectories(uploadDir);
        Files.copy(file.getInputStream(), uploadDir.resolve(filename));

        return ResponseEntity.ok(Map.of("url", "/uploads/" + filename));
    }
}
