package vn.garage.repair.api;

import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import vn.garage.repair.application.SlotGenerator;
import vn.garage.repair.application.SlotGenerator.PublicSlot;

@RestController
@RequestMapping("/api/public/slots")
public class PublicSlotsController {
    private final SlotGenerator slots;

    public PublicSlotsController(SlotGenerator slots) {
        this.slots = slots;
    }

    @GetMapping
    public List<PublicSlot> list(@RequestParam LocalDate date, @RequestParam String vehicleType) {
        return slots.availability(date, vehicleType);
    }
}
