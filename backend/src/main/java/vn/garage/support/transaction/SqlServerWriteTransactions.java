package vn.garage.support.transaction;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class SqlServerWriteTransactions {
    public static final int LOCK_TIMEOUT_MILLIS = 5_000;
    private static final int MAX_DEADLOCK_RETRIES = 2;

    private final JdbcTemplate db;
    private final TransactionTemplate transactions;

    public SqlServerWriteTransactions(PlatformTransactionManager transactionManager, JdbcTemplate db) {
        this.db = db;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.transactions.setReadOnly(false);
    }

    /** Each retry runs after the previous TransactionTemplate has rolled its transaction back. */
    public <T> T execute(Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("WRITE_TRANSACTION_MUST_BE_OUTERMOST");
        }
        for (int deadlockRetry = 0; ; deadlockRetry++) {
            try {
                return transactions.execute(status -> withLockTimeout(work));
            } catch (RuntimeException failure) {
                int error = sqlServerErrorCode(failure);
                if (error == 1222) throw new LockTimeoutException(failure);
                if (error == 1205) {
                    if (deadlockRetry < MAX_DEADLOCK_RETRIES) continue;
                    throw new DeadlockRetriesExhaustedException(failure);
                }
                throw failure;
            }
        }
    }

    private <T> T withLockTimeout(Supplier<T> work) {
        Integer previous = db.queryForObject("SELECT @@LOCK_TIMEOUT", Integer.class);
        if (previous == null) throw new IllegalStateException("SQLSERVER_LOCK_TIMEOUT_UNAVAILABLE");
        db.execute("SET LOCK_TIMEOUT " + LOCK_TIMEOUT_MILLIS);
        Throwable workFailure = null;
        try {
            return work.get();
        } catch (RuntimeException | Error failure) {
            workFailure = failure;
            throw failure;
        } finally {
            try {
                db.execute("SET LOCK_TIMEOUT " + previous);
            } catch (RuntimeException restoreFailure) {
                if (workFailure != null) workFailure.addSuppressed(restoreFailure);
                else throw restoreFailure;
            }
        }
    }

    public static int sqlServerErrorCode(Throwable root) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var pending = new ArrayDeque<Throwable>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!seen.add(current)) continue;
            if (current instanceof SQLException sql) {
                for (SQLException item = sql; item != null; item = item.getNextException()) {
                    if (item.getErrorCode() == 1205 || item.getErrorCode() == 1222) return item.getErrorCode();
                    if (item.getCause() != null) pending.addLast(item.getCause());
                }
            }
            if (current.getCause() != null) pending.addLast(current.getCause());
            Collections.addAll(pending, current.getSuppressed());
        }
        return 0;
    }

    public static final class LockTimeoutException extends RuntimeException {
        public LockTimeoutException(Throwable cause) { super(cause); }
    }

    public static final class DeadlockRetriesExhaustedException extends RuntimeException {
        public DeadlockRetriesExhaustedException(Throwable cause) { super(cause); }
    }
}
