package vn.garage.repair.infrastructure;

import java.time.Clock;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.garage.repair.application.SlotGenerator;

@Component
public class ScheduleJobs implements ApplicationRunner {
    private final SlotGenerator generator;

    @Bean
    static Clock scheduleClock() {
        return Clock.systemUTC();
    }

    public ScheduleJobs(SlotGenerator generator) {
        this.generator = generator;
    }

    @Override
    public void run(ApplicationArguments args) {
        generator.regenerate();
    }

    @Scheduled(cron = "0 5 0 * * *", zone = SlotGenerator.TIME_ZONE)
    public void daily() {
        generator.regenerate();
    }
}
