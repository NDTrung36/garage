package vn.garage.billing.api;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import vn.garage.billing.application.InvoicePaymentCommands;
import vn.garage.identity.infrastructure.GaragePrincipal;
import vn.garage.support.idempotency.IdempotencyExecutor;

@RestController
@RequestMapping("/api/admin/invoices")
public class AdminInvoicePaymentsController {
    private final InvoicePaymentCommands commands;
    private final InvoiceRequests requests;

    AdminInvoicePaymentsController(InvoicePaymentCommands commands, InvoiceRequests requests) {
        this.commands = commands;
        this.requests = requests;
    }

    @PostMapping(path = "/{id}/reset-payment", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<InvoicePaymentCommands.PaymentResponse> resetPayment(@PathVariable String id,
            @RequestHeader("Idempotency-Key") String key, @RequestBody byte[] json, Authentication authentication) {
        long invoiceId = InvoiceRequests.id(id);
        var body = requests.paymentBody(json, "reason", "INVALID_PAYMENT_RESET_REASON");
        IdempotencyExecutor.validateKey(key);
        var actor = (GaragePrincipal) authentication.getPrincipal();
        var result = commands.resetPayment(invoiceId, body.expectedVersion(), body.text(), key, actor.id(), actor.role());
        return ResponseEntity.status(result.httpStatus()).cacheControl(CacheControl.noStore()).body(result.body());
    }
}
