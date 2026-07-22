package my.edu.ukm.ftsm.ecommerce.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Serves uploaded images from the local `uploads/` directory as static files. */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // "file:" prefix + trailing "/" required by Spring's resource location format.
        String location = "file:" + UploadPathResolver.resolve(uploadDir) + "/";
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(location);
    }
}
