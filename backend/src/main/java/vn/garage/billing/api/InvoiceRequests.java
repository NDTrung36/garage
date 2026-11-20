package vn.garage.billing.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.io.IOException;
import org.springframework.stereotype.Component;

@Component
class InvoiceRequests {
    private final ObjectReader reader;

    InvoiceRequests(ObjectMapper mapper) {
        reader = mapper.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    PaymentBody paymentBody(byte[] json, String textField, String textError) {
        JsonNode body;
        try {
            body = reader.readTree(json);
        } catch (IOException failure) {
            throw new IllegalArgumentException("INVALID_INPUT", failure);
        }
        if (body == null || !body.isObject()) throw new IllegalArgumentException("INVALID_INPUT");
        body.fieldNames().forEachRemaining(field -> {
            if (!field.equals("expectedVersion") && !field.equals(textField)) {
                throw new IllegalArgumentException("INVALID_INPUT");
            }
        });
        JsonNode version = body.path("expectedVersion");
        if (!version.isIntegralNumber() || !version.canConvertToLong() || version.longValue() < 0) {
            throw new IllegalArgumentException("INVALID_INPUT");
        }
        JsonNode text = body.path(textField);
        if (!text.isTextual()) throw new IllegalArgumentException(textError);
        return new PaymentBody(version.longValue(), text.textValue());
    }

    static long id(String value) {
        if (!value.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("INVALID_INPUT");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("INVALID_INPUT", invalid);
        }
    }

    record PaymentBody(long expectedVersion, String text) { }
}
