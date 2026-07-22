package my.edu.ukm.ftsm.ecommerce.config;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Keeps upload storage stable when the app is started from the repo root or backend/. */
public final class UploadPathResolver {

    private UploadPathResolver() {}

    public static Path resolve(String uploadDir) {
        return resolve(uploadDir, Paths.get("").toAbsolutePath());
    }

    static Path resolve(String uploadDir, Path cwd) {
        Path configured = Paths.get(uploadDir == null || uploadDir.isBlank() ? "uploads" : uploadDir);
        if (configured.isAbsolute()) {
            return configured.normalize();
        }

        if (cwd.getFileName() != null
                && "backend".equals(cwd.getFileName().toString())
                && configured.getNameCount() == 1
                && "uploads".equals(configured.toString())) {
            return cwd.getParent().resolve(configured).normalize();
        }

        return cwd.resolve(configured).normalize();
    }
}
