package vn.garage.repair.api;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vn.garage.catalog.api.ServiceController;
import vn.garage.catalog.application.ServiceCatalog;
import vn.garage.identity.api.IdentityExceptionHandler;
import vn.garage.repair.application.SlotGenerator;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GarageScheduleApiTest {
    private ServiceCatalog catalog;
    private SlotGenerator slots;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        catalog = mock(ServiceCatalog.class);
        slots = mock(SlotGenerator.class);
        mvc = MockMvcBuilders.standaloneSetup(new ServiceController(catalog), new PublicSlotsController(slots))
                .setControllerAdvice(new IdentityExceptionHandler(), new ScheduleErrors()).build();
    }

    @Test
    void rejectsMissingAndFractionalServicePricesBeforeWriting() throws Exception {
        String base = """
                {"code":"OIL", "name":"Oil", "vehicleType":"CAR"}
                """;
        mvc.perform(post("/api/admin/services").contentType(MediaType.APPLICATION_JSON).content(base))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/services").contentType(MediaType.APPLICATION_JSON).content("""
                {"code":"OIL", "name":"Oil", "vehicleType":"CAR", "referencePrice":12.9}
                """)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REFERENCE_PRICE"));
        mvc.perform(patch("/api/admin/services/1").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"Oil", "vehicleType":"CAR", "expectedVersion":0, "referencePrice":-0.5}
                """)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REFERENCE_PRICE"));
        verifyNoInteractions(catalog);
    }

    @Test
    void returnsPageAndFeatureSpecificErrors() throws Exception {
        when(catalog.adminList(1, 20)).thenReturn(new ServiceCatalog.ServicePage(List.of(), 1, 20, 1, 1));
        mvc.perform(get("/api/admin/services").param("page", "1").param("size", "20"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.items").isArray());
        when(catalog.create("OIL", "Oil", null, "CAR", 12)).thenThrow(new ServiceCatalog.DuplicateCode());
        mvc.perform(post("/api/admin/services").contentType(MediaType.APPLICATION_JSON).content("""
                {"code":"OIL", "name":"Oil", "vehicleType":"CAR", "referencePrice":12}
                """)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SERVICE_CODE_EXISTS"));
        when(catalog.update(99, "Oil", null, "CAR", null, false, 0)).thenThrow(new ServiceCatalog.NotFound());
        mvc.perform(patch("/api/admin/services/99").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"Oil", "vehicleType":"CAR", "expectedVersion":0, "active":false}
                """)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SERVICE_NOT_FOUND"));
    }

    @Test
    void exposesRetryableErrorWhenScheduleIsUnavailable() throws Exception {
        var date = LocalDate.parse("2031-01-06");
        when(slots.availability(date, "CAR")).thenThrow(new SlotGenerator.ScheduleException(503, "SLOT_SCHEDULE_UNAVAILABLE"));
        mvc.perform(get("/api/public/slots").param("date", date.toString()).param("vehicleType", "CAR"))
                .andExpect(status().isServiceUnavailable()).andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("SLOT_SCHEDULE_UNAVAILABLE"));
    }
}
