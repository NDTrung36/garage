package vn.garage.billing.api;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.garage.support.idempotency.IdempotencyExecutor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BillingErrorsTest {
    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new CommandStub()).setControllerAdvice(new BillingErrors()).build();

    @Test void lockTimeoutIncludesRetryHeader() throws Exception {
        mvc.perform(post("/timeout"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("REQUEST_IN_PROGRESS"));
    }

    @Test void differentPayloadReturnsConflict() throws Exception {
        mvc.perform(post("/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @RestController
    static class CommandStub {
        @PostMapping("/timeout")
        void timeout() { throw new IdempotencyExecutor.RequestInProgressException(new IllegalStateException()); }

        @PostMapping("/conflict")
        void conflict() { throw new IdempotencyExecutor.IdempotencyConflictException(); }
    }
}
