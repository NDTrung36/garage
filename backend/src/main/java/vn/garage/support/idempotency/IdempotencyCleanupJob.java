package vn.garage.support.idempotency;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@Component
public class IdempotencyCleanupJob {
    private static final int BATCH_SIZE = 500;
    private final JdbcIdempotencyStore store;
    private final Clock clock;

    @Autowired
    public IdempotencyCleanupJob(JdbcIdempotencyStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public IdempotencyCleanupJob(JdbcIdempotencyStore store) { this(store, Clock.systemUTC()); }

    public IdempotencyCleanupJob(JdbcTemplate db) {
        this(new JdbcIdempotencyStore(db), Clock.systemUTC());
    }

    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Ho_Chi_Minh")
    public int cleanExpiredBatch() {
        return store.deleteExpiredBatch(BATCH_SIZE,
                OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC));
    }
}
