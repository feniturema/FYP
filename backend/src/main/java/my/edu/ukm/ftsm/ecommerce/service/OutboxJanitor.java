package my.edu.ukm.ftsm.ecommerce.service;

import my.edu.ukm.ftsm.ecommerce.repository.OutboxDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Hourly purge of SENT outbox rows older than 3 days, only for events the reconciler has already
 * closed, so its published counts stay exact until then.
 */
@Component
public class OutboxJanitor {

    private static final Logger log = LoggerFactory.getLogger(OutboxJanitor.class);
    static final Duration RETENTION = Duration.ofDays(3);

    private final OutboxDao outboxDao;
    private final Clock clock;

    @Autowired
    public OutboxJanitor(OutboxDao outboxDao) {
        this(outboxDao, Clock.systemUTC());
    }

    OutboxJanitor(OutboxDao outboxDao, Clock clock) {
        this.outboxDao = outboxDao;
        this.clock = clock;
    }

    @Scheduled(cron = "0 17 * * * *")
    public void purge() {
        try {
            int deleted = outboxDao.deleteSentReconciledBefore(clock.instant().minus(RETENTION));
            if (deleted > 0) {
                log.info("[SecKill] outbox janitor deleted {} SENT rows of reconciled events", deleted);
            }
        } catch (RuntimeException e) {
            log.warn("[SecKill] outbox janitor failed, will retry next hour: {}", e.toString());
        }
    }
}
