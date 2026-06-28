package ru.yandex.practicum.bionicpro.reports;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import ru.yandex.practicum.bionicpro.reports.storage.ReportStorageProperties;

@SpringBootApplication
@EnableConfigurationProperties
public class BionicproReportsApplication {

    public static void main(String[] args) {
        SpringApplication.run(BionicproReportsApplication.class, args);
    }

}
