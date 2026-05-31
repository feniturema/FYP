package my.edu.ukm.ftsm.ecommerce.config;

import my.edu.ukm.ftsm.ecommerce.model.Product;
import my.edu.ukm.ftsm.ecommerce.model.Role;
import my.edu.ukm.ftsm.ecommerce.model.User;
import my.edu.ukm.ftsm.ecommerce.repository.ProductRepository;
import my.edu.ukm.ftsm.ecommerce.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.List;

/**
 * Seeds an admin account and a few sample B2C products on first boot so the
 * platform is demoable immediately. Idempotent — skips anything that exists.
 */
@Configuration
public class DataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    @Bean
    CommandLineRunner seed(@Value("${app.seed.enabled:true}") boolean enabled,
                           @Value("${app.seed.admin-email}") String adminEmail,
                           @Value("${app.seed.admin-password}") String adminPassword,
                           UserRepository userRepository,
                           ProductRepository productRepository,
                           PasswordEncoder encoder) {
        return args -> {
            if (!enabled) {
                return;
            }
            if (!userRepository.existsByEmail(adminEmail)) {
                userRepository.save(User.builder()
                        .name("FTSM Admin")
                        .email(adminEmail)
                        .passwordHash(encoder.encode(adminPassword))
                        .role(Role.ADMIN)
                        .emailVerified(true)
                        .build());
                log.info("[Seed] created admin user {}", adminEmail);
            }
            if (productRepository.count() == 0) {
                productRepository.saveAll(List.of(
                        Product.builder().name("FTSM Hoodie").description("Official faculty hoodie")
                                .price(new BigDecimal("79.00")).totalStock(100).category("Apparel")
                                .imageUrl("https://placehold.co/400x400?text=Hoodie").build(),
                        Product.builder().name("UKM Tumbler").description("Stainless steel tumbler")
                                .price(new BigDecimal("35.00")).totalStock(200).category("Lifestyle")
                                .imageUrl("https://placehold.co/400x400?text=Tumbler").build(),
                        Product.builder().name("Limited Edition Lanyard").description("Flash-sale lanyard")
                                .price(new BigDecimal("15.00")).totalStock(50).category("Accessories")
                                .imageUrl("https://placehold.co/400x400?text=Lanyard").build()
                ));
                log.info("[Seed] inserted sample products");
            }
        };
    }
}
