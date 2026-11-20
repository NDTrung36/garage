package vn.garage.repair.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class RepairErrorsTest {
    @Test
    void requestInProgressIs503AndCanBeRetriedWithSameKey() {
        var response = new RepairErrors().requestInProgress();

        assertEquals(503, response.getStatusCode().value());
        assertEquals("1", response.getHeaders().getFirst("Retry-After"));
        assertEquals("REQUEST_IN_PROGRESS", response.getBody().code());
    }

    @Test
    void idempotencyKeyConflictUsesFeatureError() {
        var response = new RepairErrors().idempotencyConflict();

        assertEquals(409, response.getStatusCode().value());
        assertEquals("IDEMPOTENCY_CONFLICT", response.getBody().code());
    }

    @Test
    void repairConstraintFailureUsesRepairCodeInsteadOfIdentityCode() {
        var response = new RepairErrors().repairConstraintConflict();

        assertEquals(409, response.getStatusCode().value());
        assertEquals("REPAIR_CONFLICT", response.getBody().code());
    }
}
