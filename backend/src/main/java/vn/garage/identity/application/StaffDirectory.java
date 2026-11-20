package vn.garage.identity.application;

import java.util.Collection;
import java.util.Map;

public interface StaffDirectory {
    Map<Long, String> displayNames(Collection<Long> staffIds);
}
