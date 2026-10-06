package my.edu.ukm.ftsm.ecommerce.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Imports {@code demo/catalog.json} at startup, under the {@code demo} profile only (docs/phases/P5a.md §6.2).
 * The guards run while the context starts, so a wrong target fails startup before anything is written:
 * {@code APP_DEMO_TARGET_DB} must equal {@code SELECT DATABASE()}, the compose default {@code ftsm_ecommerce} also needs
 * {@code APP_DEMO_ALLOW_DEFAULT_DB=true}, and {@code SEED_DEMO_PASSWORD} must have at least 12 characters.
 */
@Component
@Profile("demo")
public class DemoCatalogSeeder implements ApplicationRunner, InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(DemoCatalogSeeder.class);
    static final String DEFAULT_DB = "ftsm_ecommerce";

    private final DemoCatalogImporter importer;
    private final JdbcTemplate jdbc;
    private final String targetDb;
    private final boolean allowDefaultDb;
    private final String sellerPassword;
    private final String onConflict;

    public DemoCatalogSeeder(DemoCatalogImporter importer, JdbcTemplate jdbc,
                             @Value("${app.demo.target-db:}") String targetDb,
                             @Value("${app.demo.allow-default-db:false}") boolean allowDefaultDb,
                             @Value("${app.demo.seller-password:}") String sellerPassword,
                             @Value("${app.demo.on-conflict:fail}") String onConflict) {
        this.importer = importer;
        this.jdbc = jdbc;
        this.targetDb = targetDb;
        this.allowDefaultDb = allowDefaultDb;
        this.sellerPassword = sellerPassword;
        this.onConflict = onConflict;
    }

    @Override
    public void afterPropertiesSet() {
        if (targetDb == null || targetDb.isBlank()) {
            throw new IllegalStateException("demo profile: APP_DEMO_TARGET_DB must name the database to import into");
        }
        String actual = jdbc.queryForObject("SELECT DATABASE()", String.class);
        if (!targetDb.equals(actual)) {
            throw new IllegalStateException("demo profile: APP_DEMO_TARGET_DB=" + targetDb
                    + " but the datasource is connected to " + actual + "; refusing to import");
        }
        if (DEFAULT_DB.equals(actual) && !allowDefaultDb) {
            throw new IllegalStateException("demo profile: refusing to import into the default database " + DEFAULT_DB
                    + " without APP_DEMO_ALLOW_DEFAULT_DB=true");
        }
        if (sellerPassword == null || sellerPassword.length() < 12) {
            throw new IllegalStateException("demo profile: SEED_DEMO_PASSWORD must be at least 12 characters");
        }
        try {
            DemoCatalogImporter.OnConflict.parse(onConflict);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("demo profile: APP_DEMO_ON_CONFLICT must be fail or skip, got " + onConflict);
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        CatalogJson catalog = CatalogJson.load().validate();
        ImportReport report = importer.importCatalog(catalog);   // through the proxy: one transaction
        log.info("[Demo] catalogue import into {}: {}", targetDb, report.summary());
        try {
            importer.adjustAutoIncrement();
        } catch (DataAccessException e) {
            log.warn("[Demo] AUTO_INCREMENT adjustment failed (the import itself is committed): {}", e.toString());
        }
    }
}
