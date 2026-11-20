package vn.garage.identity.infrastructure;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import vn.garage.identity.application.StaffDirectory;

@Repository
public class JpaStaffDirectory implements StaffDirectory {
    private final UserJpaRepository users;

    public JpaStaffDirectory(UserJpaRepository users) { this.users = users; }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, String> displayNames(Collection<Long> staffIds) {
        if (staffIds == null || staffIds.isEmpty()) return Map.of();
        return users.findDisplayNamesByIdIn(staffIds).stream()
                .collect(Collectors.toMap(UserJpaRepository.StaffDisplayName::getId,
                        UserJpaRepository.StaffDisplayName::getDisplayName));
    }
}
