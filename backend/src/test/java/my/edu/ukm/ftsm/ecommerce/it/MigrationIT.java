package my.edu.ukm.ftsm.ecommerce.it;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A fresh MySQL 8.0.46 migrates to exactly V1, V2 (and V1.1 if it ever exists), all successful, and validates. */
class MigrationIT extends AbstractIntegrationTest {

    @Autowired
    Flyway flyway;

    @Test
    void historyIsV1V2AllSuccessfulAndValidates() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT version, CAST(success AS UNSIGNED) AS success FROM flyway_schema_history "
                        + "WHERE version IS NOT NULL ORDER BY installed_rank");

        List<String> versions = rows.stream().map(r -> String.valueOf(r.get("version"))).toList();
        assertThat(versions).isIn(List.of("1", "2"), List.of("1", "1.1", "2"));
        assertThat(rows).allSatisfy(r -> assertThat(((Number) r.get("success")).intValue()).isEqualTo(1));

        flyway.validate();   // throws FlywayValidateException on checksum / pending / missing migrations
    }
}
