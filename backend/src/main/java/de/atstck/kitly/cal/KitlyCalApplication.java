package de.atstck.kitly.cal;

import de.atstck.kitly.cal.config.CalProperties;
import de.atstck.kitly.cal.google.GoogleProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({CalProperties.class, GoogleProperties.class})
public class KitlyCalApplication {

    public static void main(String[] args) {
        SpringApplication.run(KitlyCalApplication.class, args);
    }
}
