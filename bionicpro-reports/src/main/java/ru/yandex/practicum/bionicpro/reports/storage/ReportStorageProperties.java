package ru.yandex.practicum.bionicpro.reports.storage;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Настройки S3-совместимого объектного хранилища.
 */
@Validated
@ConfigurationProperties(prefix = "bionicpro.reports.storage")
public record ReportStorageProperties(

        @NotBlank
        String endpoint,

        @NotBlank
        String region,

        @NotBlank
        String bucket,

        @NotBlank
        String accessKey,

        @NotBlank
        String secretKey,

        @NotBlank
        String objectKeySecret
) {
}