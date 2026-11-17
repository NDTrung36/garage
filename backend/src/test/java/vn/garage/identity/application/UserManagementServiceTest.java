package vn.garage.identity.application;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import java.util.Optional;
import vn.garage.identity.infrastructure.*;
import org.springframework.security.crypto.password.PasswordEncoder;

class UserManagementServiceTest {
    @Test void normalizesUsernameUsingRootLocale() {
        assertEquals("garage.admin-1", UserManagementService.normalizeUsername("  GARAGE.Admin-1  "));
    }

    @Test void rejectsInvalidUsernameCharactersAndLength() {
        assertThrows(IllegalArgumentException.class, () -> UserManagementService.normalizeUsername("aa@bb"));
        assertThrows(IllegalArgumentException.class, () -> UserManagementService.normalizeUsername("ab"));
    }

    @Test void permitsDowngradingOneOfTwoAdmins() {
        UserJpaRepository users=mock(UserJpaRepository.class); PasswordEncoder encoder=mock(PasswordEncoder.class);
        UserEntity actor=UserEntity.create("admin1","hash","Admin 1","ADMIN");
        UserEntity target=UserEntity.create("admin2","hash","Admin 2","ADMIN");
        try { var id=UserEntity.class.getDeclaredField("id"); id.setAccessible(true); id.set(actor,1L); id.set(target,2L); } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        when(users.findById(1L)).thenReturn(Optional.of(actor)); when(users.findById(2L)).thenReturn(Optional.of(target));
        when(users.countByActiveTrueAndRole("ADMIN")).thenReturn(2L); when(users.saveAndFlush(target)).thenReturn(target);
        var result=new UserManagementService(users,encoder).update(1,2,new UserManagementService.Update(null,"STAFF",null,0L));
        assertEquals("STAFF",result.role()); assertEquals(1,target.getAuthVersion());
    }

    @Test void reportsMissingTargetAsNotFound() {
        UserJpaRepository users=mock(UserJpaRepository.class); when(users.findById(1L)).thenReturn(Optional.empty());
        assertThrows(UserManagementService.UserNotFoundException.class,()->new UserManagementService(users,mock(PasswordEncoder.class))
            .update(1,2,new UserManagementService.Update("Name",null,null,0L)));
    }
}
