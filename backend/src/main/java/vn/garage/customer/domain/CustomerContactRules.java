package vn.garage.customer.domain;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import java.util.Locale;

public final class CustomerContactRules {
    private static final PhoneNumberUtil PHONES = PhoneNumberUtil.getInstance();

    private CustomerContactRules() { }

    public static String normalizeVietnameseMobile(String input) {
        if (input == null || input.isBlank() || input.length() > 40) {
            throw new IllegalArgumentException("INVALID_PHONE");
        }
        String value = input.strip();
        if (!value.matches("(?U)(?:0[0-9\\s().-]*|\\+84[0-9\\s().-]*)")) {
            throw new IllegalArgumentException("INVALID_PHONE");
        }
        try {
            Phonenumber.PhoneNumber parsed = PHONES.parse(value, "VN");
            if (!PHONES.isValidNumberForRegion(parsed, "VN")
                    || PHONES.getNumberType(parsed) != PhoneNumberUtil.PhoneNumberType.MOBILE) {
                throw new IllegalArgumentException("INVALID_PHONE");
            }
            return PHONES.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164);
        } catch (NumberParseException ex) {
            throw new IllegalArgumentException("INVALID_PHONE", ex);
        }
    }

    public static String normalizeName(String input) {
        if (input == null) throw new IllegalArgumentException("INVALID_CONTACT_NAME");
        String value = input.strip().replaceAll("(?U)\\s+", " ");
        int characters = value.codePointCount(0, value.length());
        if (characters < 2 || value.length() > 100) throw new IllegalArgumentException("INVALID_CONTACT_NAME");
        return value;
    }

    public static String normalizePlate(String input) {
        if (input == null) throw new IllegalArgumentException("INVALID_PLATE");
        String value = input.strip().toUpperCase(Locale.ROOT).replaceAll("(?U)[\\p{Z}\\s.-]+", "");
        if (value.isEmpty() || value.length() > 20 || !value.matches("[A-Z0-9]+")) {
            throw new IllegalArgumentException("INVALID_PLATE");
        }
        return value;
    }

    public static String normalizePlateDisplay(String input) {
        if (input == null) throw new IllegalArgumentException("INVALID_PLATE");
        String value = input.strip().toUpperCase(Locale.ROOT);
        if (value.isEmpty() || value.codePointCount(0, value.length()) > 20
                || !value.matches("(?U)[A-Z0-9\\s.\\-]+")) {
            throw new IllegalArgumentException("INVALID_PLATE");
        }
        return value;
    }

    public static String normalizeOptionalVehicleText(String input) {
        if (input == null) return null;
        String value = input.strip().replaceAll("(?U)\\s+", " ");
        if (value.isEmpty()) return null;
        if (value.length() > 100) throw new IllegalArgumentException("INVALID_VEHICLE");
        return value;
    }
}
