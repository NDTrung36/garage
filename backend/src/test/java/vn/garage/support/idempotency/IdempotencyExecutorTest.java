package vn.garage.support.idempotency;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.garage.support.transaction.SqlServerWriteTransactions;

class IdempotencyExecutorTest {
    private static final String USE_CASE = "inventory.stock.receipt";
    private static final Long ACTOR_ID = 41L;
    private static final String KEY = "Key-01";
    private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(Instant.parse("2031-01-06T01:00:00Z"), ZoneOffset.UTC);
    private JdbcIdempotencyStore store;
    private SqlServerWriteTransactions writes;
    private IdempotencyExecutor executor;

    @BeforeEach
    void setUp() {
        store = mock(JdbcIdempotencyStore.class);
        writes = mock(SqlServerWriteTransactions.class);
        when(writes.execute(any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
        executor = new IdempotencyExecutor(store, writes, new ObjectMapper(), Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
    }

    @Test
    void canonicalHashIgnoresObjectKeyOrderAndDecimalScale() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("quantity", new BigDecimal("1.2"));
        first.put("partId", 8L);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("partId", 8L);
        second.put("quantity", new BigDecimal("1.200"));

        assertEquals(IdempotencyExecutor.hash(new ObjectMapper(), first),
                IdempotencyExecutor.hash(new ObjectMapper(), second));
    }

    @Test
    void replaysSavedStatusAndBodyForSameHash() throws Exception {
        Map<String, Object> storedCommand = new LinkedHashMap<>();
        storedCommand.put("quantity", new BigDecimal("1.2"));
        storedCommand.put("repairItemId", 3L);
        Map<String, Object> retriedCommand = new LinkedHashMap<>();
        retriedCommand.put("repairItemId", 3L);
        retriedCommand.put("quantity", new BigDecimal("1.200"));
        String hash = IdempotencyExecutor.hash(new ObjectMapper(), storedCommand);
        var body = new ResultBody("MV-100", "0.000", "1.200");
        when(store.lock(eq(IdempotencyExecutor.scope(USE_CASE, ACTOR_ID)), eq(KEY), any())).thenReturn(
                new JdbcIdempotencyStore.StoredResult(hash, 201, new ObjectMapper().writeValueAsString(body), null, true));
        var mutations = new AtomicInteger();

        var result = executor.execute(USE_CASE, ACTOR_ID, KEY, retriedCommand, ResultBody.class, () -> {
            mutations.incrementAndGet();
            return new IdempotencyExecutor.Success<>(201, new ResultBody("wrong", "0", "0"));
        });

        assertEquals(201, result.httpStatus());
        assertEquals(body, result.body());
        assertTrue(result.replayed());
        assertEquals(0, mutations.get());
        verify(store, never()).save(anyString(), anyString(), anyString(), anyInt(), anyString(), any(), any());
        verify(store).lock(eq(IdempotencyExecutor.scope(USE_CASE, ACTOR_ID)), eq(KEY), eq(NOW));
    }

    @Test
    void changedCommandConflictsAndDoesNotRunMutation() {
        var prior = Map.of("partId", 8L, "quantity", new BigDecimal("1.2"));
        var changed = Map.of("partId", 9L, "quantity", new BigDecimal("1.200"));
        String previousHash = IdempotencyExecutor.hash(new ObjectMapper(), prior);
        when(store.lock(eq(IdempotencyExecutor.scope(USE_CASE, ACTOR_ID)), eq(KEY), any())).thenReturn(
                new JdbcIdempotencyStore.StoredResult(previousHash, 201, "{}", null, true));
        var mutations = new AtomicInteger();

        assertThrows(IdempotencyExecutor.IdempotencyConflictException.class, () -> executor.execute(USE_CASE, ACTOR_ID, KEY, changed,
                ResultBody.class, () -> {
                    mutations.incrementAndGet();
                    return new IdempotencyExecutor.Success<>(201, new ResultBody("MV", "0", "1"));
                }));
        assertEquals(0, mutations.get());
    }

    @Test
    void keyIsCaseSensitiveAndScopeSeparatesActorAndCommand() {
        when(store.lock(anyString(), anyString(), any())).thenReturn(null);
        var first = executor.execute("inventory.stock.receipt", 41L, "Key-01", Map.of("id", 1),
                ResultBody.class, () -> new IdempotencyExecutor.Success<>(201, new ResultBody("A", "0", "1")));
        var second = executor.execute("inventory.stock.receipt", 42L, "key-01", Map.of("id", 1),
                ResultBody.class, () -> new IdempotencyExecutor.Success<>(201, new ResultBody("B", "0", "1")));

        assertFalse(first.replayed());
        assertFalse(second.replayed());
        verify(store).lock(eq("inventory.stock.receipt:actor:41"), eq("Key-01"), any());
        verify(store).lock(eq("inventory.stock.receipt:actor:42"), eq("key-01"), any());
        assertNotEquals(IdempotencyExecutor.scope("inventory.stock.receipt", 41L),
                IdempotencyExecutor.scope("inventory.stock.adjustment", 41L));
    }

    @Test
    void differentlyCasedKeysAreIndependentWithinTheSameActorAndUseCase() {
        when(store.lock(anyString(), anyString(), any())).thenReturn(null);

        var upper = executor.execute(USE_CASE, ACTOR_ID, "Key-01", Map.of("partId", 1), ResultBody.class,
                () -> new IdempotencyExecutor.Success<>(201, new ResultBody("UPPER", "0", "1")));
        var lower = executor.execute(USE_CASE, ACTOR_ID, "key-01", Map.of("partId", 1), ResultBody.class,
                () -> new IdempotencyExecutor.Success<>(201, new ResultBody("LOWER", "0", "1")));

        assertEquals("UPPER", upper.body().movementId());
        assertEquals("LOWER", lower.body().movementId());
        verify(store).lock(eq("inventory.stock.receipt:actor:41"), eq("Key-01"), any());
        verify(store).lock(eq("inventory.stock.receipt:actor:41"), eq("key-01"), any());
    }

    @Test
    void storesSuccessForAnEmptyIdempotencyKey() {
        var command = Map.of("id", 1);
        when(store.lock(anyString(), anyString(), any())).thenReturn(null);

        executor.execute(USE_CASE, ACTOR_ID, KEY, command, ResultBody.class,
                () -> new IdempotencyExecutor.Success<>(200, new ResultBody("NEW", "0", "1")));

        verify(store).save(eq("inventory.stock.receipt:actor:41"), eq(KEY), anyString(), eq(200), anyString(),
                eq(NOW), eq(NOW.plusHours(24)));
    }

    @Test
    void validatesAsciiKeyAndScope() {
        assertThrows(IdempotencyExecutor.InvalidIdempotencyKeyException.class, () -> executor.execute(USE_CASE, ACTOR_ID, "", Map.of(),
                ResultBody.class, () -> new IdempotencyExecutor.Success<>(201, new ResultBody("x", "0", "1"))));
        assertThrows(IdempotencyExecutor.InvalidIdempotencyKeyException.class, () -> executor.execute(USE_CASE, ACTOR_ID, "khóa", Map.of(),
                ResultBody.class, () -> new IdempotencyExecutor.Success<>(201, new ResultBody("x", "0", "1"))));
        assertThrows(IdempotencyExecutor.InvalidIdempotencyKeyException.class, () -> executor.execute(USE_CASE, ACTOR_ID, "x".repeat(101), Map.of(),
                ResultBody.class, () -> new IdempotencyExecutor.Success<>(201, new ResultBody("x", "0", "1"))));
        assertThrows(IllegalArgumentException.class, () -> executor.execute("x".repeat(81), ACTOR_ID, KEY, Map.of(),
                ResultBody.class, () -> new IdempotencyExecutor.Success<>(201, new ResultBody("x", "0", "1"))));
        verifyNoInteractions(store, writes);
    }

    @Test
    void acceptsTheMaximumHundredCharacterAsciiKey() {
        String key = "A".repeat(100);
        when(store.lock(anyString(), eq(key), any())).thenReturn(null);

        var result = executor.execute(USE_CASE, ACTOR_ID, key, Map.of("partId", 8L), ResultBody.class,
                () -> new IdempotencyExecutor.Success<>(201, new ResultBody("MV", "0", "1")));

        assertFalse(result.replayed());
        verify(store).save(eq("inventory.stock.receipt:actor:41"), eq(key), anyString(), eq(201), anyString(),
                eq(NOW), eq(NOW.plusHours(24)));
    }

    @Test
    void mapsLockTimeoutAfterRollbackToRetryableRequest() {
        doThrow(new SqlServerWriteTransactions.LockTimeoutException(
                new IllegalStateException("SQL Server 1222"))).when(writes).execute(any());

        assertThrows(IdempotencyExecutor.RequestInProgressException.class, () -> executor.execute(
                USE_CASE, ACTOR_ID, KEY, Map.of("partId", 8L), ResultBody.class,
                () -> new IdempotencyExecutor.Success<>(201, new ResultBody("MV", "0", "1"))));
    }

    public record ResultBody(String movementId, String before, String after) { }
}
