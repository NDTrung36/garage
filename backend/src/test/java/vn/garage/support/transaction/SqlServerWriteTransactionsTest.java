package vn.garage.support.transaction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class SqlServerWriteTransactionsTest {
    private PlatformTransactionManager manager;
    private JdbcTemplate db;
    private SqlServerWriteTransactions transactions;

    @BeforeEach
    void setUp() {
        manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(mock(TransactionStatus.class));
        db = mock(JdbcTemplate.class);
        when(db.queryForObject("SELECT @@LOCK_TIMEOUT", Integer.class)).thenReturn(-1);
        transactions = new SqlServerWriteTransactions(manager, db);
    }

    @Test
    void retriesDeadlockTwiceAndRestoresOriginalSessionTimeoutOnEveryAttempt() {
        var calls = new AtomicInteger();

        String result = transactions.execute(() -> {
            if (calls.getAndIncrement() < 2) {
                throw sqlFailure(1205);
            }
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(3, calls.get());
        verify(db, times(3)).execute("SET LOCK_TIMEOUT 5000");
        verify(db, times(3)).execute("SET LOCK_TIMEOUT -1");
    }

    @Test
    void convertsLockTimeoutAndRestoresSessionSettingBeforeReturning() {
        assertThrows(SqlServerWriteTransactions.LockTimeoutException.class,
                () -> transactions.execute(() -> { throw sqlFailure(1222); }));

        verify(db).execute("SET LOCK_TIMEOUT 5000");
        verify(db).execute("SET LOCK_TIMEOUT -1");
        verify(manager).rollback(any(TransactionStatus.class));
    }

    private static DataAccessResourceFailureException sqlFailure(int code) {
        return new DataAccessResourceFailureException("SQL Server error " + code,
                new SQLException("SQL Server error " + code, "S0001", code));
    }
}
