package vn.garage.repair.api;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.garage.identity.infrastructure.GaragePrincipal;
import vn.garage.identity.infrastructure.SecurityConfiguration;
import vn.garage.identity.infrastructure.SessionValidityService;
import vn.garage.identity.infrastructure.UserEntity;
import vn.garage.identity.infrastructure.UserJpaRepository;
import vn.garage.repair.application.RepairCompletionService;
import vn.garage.repair.application.RepairOrderQuery;
import vn.garage.repair.application.RepairOrderService;
import vn.garage.repair.application.StaffBookingQuery;
import vn.garage.repair.application.VehicleIntakeService;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({RepairOrdersController.class, StaffBookingsController.class})
@Import({SecurityConfiguration.class, SessionValidityService.class, RepairSecurityTest.ClockConfig.class})
class RepairSecurityTest {
    private static final String ORDERS = "/api/staff/repair-orders";
    private static final String BOOKINGS = "/api/staff/bookings";
    private static final String CHECK_IN = BOOKINGS + "/8/check-in";
    private static final String COMPLETE = ORDERS + "/9/complete";

    @Autowired MockMvc mvc;
    @MockitoBean RepairOrderQuery orders;
    @MockitoBean RepairOrderService edits;
    @MockitoBean RepairCompletionService completion;
    @MockitoBean StaffBookingQuery bookings;
    @MockitoBean VehicleIntakeService intake;
    @MockitoBean UserJpaRepository users;

    @Test void guestCannotReadOrWriteInternalIntakeAndRepair() throws Exception {
        mvc.perform(get(BOOKINGS).servletPath(BOOKINGS)).andExpect(status().isUnauthorized());
        mvc.perform(get(ORDERS).servletPath(ORDERS)).andExpect(status().isUnauthorized());
        mvc.perform(command(CHECK_IN).with(csrf().asHeader())).andExpect(status().isUnauthorized());
        mvc.perform(command(COMPLETE).with(csrf().asHeader())).andExpect(status().isUnauthorized());
        verifyNoInteractions(orders, bookings, completion, edits, intake);
    }

    @Test void bothRolesReadAndWriteOnlyWithCsrf() throws Exception {
        when(orders.list(null, 0, 20)).thenReturn(new RepairOrderQuery.Page(List.of(), 0, 20, 0, 0));
        for (String role : List.of("STAFF", "ADMIN")) {
            long id = role.equals("STAFF") ? 41 : 77;
            var session = session(id, role, 0);
            mvc.perform(get(ORDERS).servletPath(ORDERS).session(session)).andExpect(status().isOk());
            mvc.perform(command(CHECK_IN).session(session)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
            mvc.perform(command(COMPLETE).session(session)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
            mvc.perform(command(CHECK_IN).session(session).with(csrf().asHeader())).andExpect(status().isOk());
            mvc.perform(command(COMPLETE).session(session).with(csrf().asHeader())).andExpect(status().isOk());
            verify(intake).checkIn(8, 0L, null, null, null, id);
            verify(completion).complete(9, 0L, id, role);
        }
        verifyNoMoreInteractions(completion, edits, intake);
    }

    @Test void outdatedAuthVersionRevokesSessionBeforeRepairWrite() throws Exception {
        var stale = session(41, "STAFF", 1);
        mvc.perform(command(COMPLETE).session(stale).with(csrf().asHeader())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
        assertTrue(stale.isInvalid());
        verify(users).findById(41L);
        verifyNoInteractions(orders, bookings, completion, edits, intake);
    }

    private MockHttpSession session(long id, String role, long storedVersion) {
        var user = UserEntity.create("sc14_user", "hash", "Nhân viên", role);
        user.setAuthVersion(storedVersion);
        when(users.findById(id)).thenReturn(Optional.of(user));
        var principal = new GaragePrincipal(id, "sc14_user", "Nhân viên", role, 0, "hash");
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }

    private static MockHttpServletRequestBuilder command(String path) {
        return post(path).servletPath(path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0}");
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean Clock testClock() { return Clock.systemUTC(); }
    }
}
