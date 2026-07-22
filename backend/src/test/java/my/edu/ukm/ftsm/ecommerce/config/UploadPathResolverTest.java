package my.edu.ukm.ftsm.ecommerce.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class UploadPathResolverTest {

    @Test
    void defaultUploadsFromBackendDirectoryResolvesToProjectRootUploads() {
        Path cwd = Path.of("/demo/FYP/backend");

        Path resolved = UploadPathResolver.resolve("uploads", cwd);

        assertThat(resolved).isEqualTo(Path.of("/demo/FYP/uploads"));
    }

    @Test
    void absoluteUploadDirectoryIsPreserved() {
        Path resolved = UploadPathResolver.resolve("/var/ftsm/uploads", Path.of("/demo/FYP/backend"));

        assertThat(resolved).isEqualTo(Path.of("/var/ftsm/uploads"));
    }
}
