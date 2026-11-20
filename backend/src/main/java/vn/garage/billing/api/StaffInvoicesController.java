package vn.garage.billing.api;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import vn.garage.billing.application.InvoicePaymentCommands;
import vn.garage.billing.application.InvoiceQuery;
import vn.garage.identity.infrastructure.GaragePrincipal;
import vn.garage.support.idempotency.IdempotencyExecutor;

@RestController
@RequestMapping("/api/staff/invoices")
public class StaffInvoicesController {
    private final InvoiceQuery query;
    private final InvoicePaymentCommands commands;
    private final InvoiceRequests requests;

    StaffInvoicesController(InvoiceQuery query, InvoicePaymentCommands commands, InvoiceRequests requests) {
        this.query = query;
        this.commands = commands;
        this.requests = requests;
    }

    @GetMapping
    public ResponseEntity<InvoiceQuery.Page> list(@RequestParam(required = false) String paymentStatus,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.list(paymentStatus, page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<InvoiceQuery.Detail> detail(@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.detail(InvoiceRequests.id(id)));
    }

    @PostMapping(path = "/{id}/mark-paid", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<InvoicePaymentCommands.PaymentResponse> markPaid(@PathVariable String id,
            @RequestHeader("Idempotency-Key") String key, @RequestBody byte[] json, Authentication authentication) {
        long invoiceId = InvoiceRequests.id(id);
        var body = requests.paymentBody(json, "paymentMethod", "INVALID_PAYMENT_METHOD");
        IdempotencyExecutor.validateKey(key);
        var actor = (GaragePrincipal) authentication.getPrincipal();
        var result = commands.markPaid(invoiceId, body.expectedVersion(), body.text(), key, actor.id(), actor.role());
        return ResponseEntity.status(result.httpStatus()).cacheControl(CacheControl.noStore()).body(result.body());
    }
}
