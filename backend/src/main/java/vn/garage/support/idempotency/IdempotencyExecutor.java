package vn.garage.support.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vn.garage.support.transaction.SqlServerWriteTransactions;

@Component
public class IdempotencyExecutor {
    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,100}");
    private static final Pattern USE_CASE_PATTERN = Pattern.compile("[a-z][a-z0-9._-]{0,79}");
    private static final Pattern HASH_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private final JdbcIdempotencyStore store;
    private final SqlServerWriteTransactions writes;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    public IdempotencyExecutor(JdbcIdempotencyStore store, SqlServerWriteTransactions writes, ObjectMapper json,
                               Clock clock) {
        this.store = store;
        this.writes = writes;
        this.json = json;
        this.clock = clock;
    }

    public IdempotencyExecutor(JdbcIdempotencyStore store, SqlServerWriteTransactions writes, ObjectMapper json) {
        this(store, writes, json, Clock.systemUTC());
    }

    public IdempotencyExecutor(SqlServerWriteTransactions writes, JdbcIdempotencyStore store, ObjectMapper json) {
        this(store, writes, json);
    }

    public IdempotencyExecutor(SqlServerWriteTransactions writes, JdbcIdempotencyStore store,
                               ObjectMapper json, Clock clock) {
        this(store, writes, json, clock);
    }

    public <T> Result<T> execute(String useCase, Long actorId, String key, Object canonicalPayload,
                                 Class<T> responseType, Supplier<? extends CommandResult<T>> command) {
        String scope = scope(useCase, actorId);
        return executeWithHash(scope, key, hash(json, canonicalPayload), responseType, command);
    }

    /** Runs under a scope which has already been composed by an internal caller. */
    public <T> Result<T> execute(String scope, String key, Object canonicalPayload, Class<T> responseType,
                                 Supplier<? extends CommandResult<T>> command) {
        validateScope(scope);
        return executeWithHash(scope, key, hash(json, canonicalPayload), responseType, command);
    }

    public <T> Result<T> executeWithHash(String scope, String key, String fingerprint, Class<T> responseType,
                                         Supplier<? extends CommandResult<T>> command) {
        validateScope(scope);
        validateKey(key);
        if (fingerprint == null || !HASH_PATTERN.matcher(fingerprint).matches()) {
            throw new IllegalArgumentException("INVALID_IDEMPOTENCY_HASH");
        }
        try {
            return writes.execute(() -> {
                OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
                JdbcIdempotencyStore.StoredResult previous = store.lock(scope, key, now);
                if (previous != null) {
                    if (!MessageDigest.isEqual(previous.requestHash().getBytes(StandardCharsets.US_ASCII),
                            fingerprint.getBytes(StandardCharsets.US_ASCII))) {
                        throw new IdempotencyConflictException();
                    }
                    return new Result<>(previous.httpStatus(), decode(previous.responseJson(), responseType), true);
                }

                CommandResult<T> success = command.get();
                if (success == null || success.body() == null || success.httpStatus() < 200 || success.httpStatus() > 299) {
                    throw new IllegalArgumentException("IDEMPOTENCY_SUCCESS_RESPONSE_REQUIRED");
                }
                OffsetDateTime savedAt = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
                store.save(scope, key, fingerprint, success.httpStatus(), encode(success.body()), savedAt, savedAt.plusHours(24));
                return new Result<>(success.httpStatus(), success.body(), false);
            });
        } catch (SqlServerWriteTransactions.LockTimeoutException timeout) {
            throw new RequestInProgressException(timeout);
        } catch (SqlServerWriteTransactions.DeadlockRetriesExhaustedException exhausted) {
            throw new RequestInProgressException(exhausted);
        }
    }

    public static String scope(String useCase, Long actorId) {
        if (useCase == null || !USE_CASE_PATTERN.matcher(useCase).matches()) {
            throw new IllegalArgumentException("INVALID_IDEMPOTENCY_SCOPE");
        }
        if (actorId == null) return useCase;
        if (actorId < 1) throw new IllegalArgumentException("INVALID_IDEMPOTENCY_ACTOR");
        String scope = useCase + ":actor:" + actorId;
        validateScope(scope);
        return scope;
    }

    public static void validateKey(String key) {
        if (key == null || !KEY_PATTERN.matcher(key).matches()) throw new InvalidIdempotencyKeyException();
    }

    public static String hash(ObjectMapper json, Object canonicalPayload) {
        if (canonicalPayload == null) throw new IllegalArgumentException("IDEMPOTENCY_PAYLOAD_REQUIRED");
        try {
            JsonNode tree = json.valueToTree(canonicalPayload);
            StringBuilder canonical = new StringBuilder();
            appendCanonical(json, tree, canonical);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("IDEMPOTENCY_FINGERPRINT_FAILED", error);
        }
    }

    private static void validateScope(String scope) {
        if (scope == null || scope.isBlank() || scope.length() > 100
                || !scope.matches("[a-z0-9._:-]{1,100}")) {
            throw new IllegalArgumentException("INVALID_IDEMPOTENCY_SCOPE");
        }
    }

    private static void appendCanonical(ObjectMapper json, JsonNode node, StringBuilder output)
            throws JsonProcessingException {
        if (node == null || node.isNull()) {
            output.append("null");
        } else if (node.isObject()) {
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> entries = node.fields();
            while (entries.hasNext()) {
                Map.Entry<String, JsonNode> entry = entries.next();
                fields.put(entry.getKey(), entry.getValue());
            }
            output.append('{');
            boolean first = true;
            for (var entry : fields.entrySet()) {
                if (!first) output.append(',');
                first = false;
                output.append(json.writeValueAsString(entry.getKey())).append(':');
                appendCanonical(json, entry.getValue(), output);
            }
            output.append('}');
        } else if (node.isArray()) {
            output.append('[');
            for (int index = 0; index < node.size(); index++) {
                if (index > 0) output.append(',');
                appendCanonical(json, node.get(index), output);
            }
            output.append(']');
        } else if (node.isNumber()) {
            BigDecimal number = node.decimalValue().stripTrailingZeros();
            output.append(number.signum() == 0 ? "0" : number.toPlainString());
        } else {
            output.append(json.writeValueAsString(node));
        }
    }

    private String encode(Object body) {
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("IDEMPOTENCY_RESPONSE_SERIALIZATION_FAILED", error);
        }
    }

    private <T> T decode(String value, Class<T> responseType) {
        try {
            return json.readValue(value, responseType);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("IDEMPOTENCY_RESPONSE_INVALID", error);
        }
    }

    public interface CommandResult<T> {
        int httpStatus();
        T body();
    }

    public record Success<T>(int httpStatus, T body) implements CommandResult<T> { }

    public record Response<T>(int status, T body) implements CommandResult<T> {
        @Override public int httpStatus() { return status; }
    }

    public record Result<T>(int httpStatus, T body, boolean replayed) {
        public int status() { return httpStatus; }
    }

    public static final class InvalidIdempotencyKeyException extends RuntimeException { }
    public static class ConflictException extends RuntimeException { }
    public static final class IdempotencyConflictException extends ConflictException { }
    public static final class RequestInProgressException extends RuntimeException {
        public RequestInProgressException(Throwable cause) { super(cause); }
    }
}
