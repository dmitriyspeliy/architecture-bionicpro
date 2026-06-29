package ru.yandex.practicum.bionicpro.reports.storage;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Настройки CDN для выдачи сформированных отчётов.
 */
@Validated
@ConfigurationProperties(prefix = "bionicpro.reports.cdn")
public record ReportCdnProperties(

        @NotBlank
        String baseUrl
) {
}