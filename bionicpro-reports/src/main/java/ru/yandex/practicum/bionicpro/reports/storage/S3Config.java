package ru.yandex.practicum.bionicpro.reports.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/**
 * Конфигурация клиента S3.
 */
@Configuration
@EnableConfigurationProperties({
        ReportStorageProperties.class,
        ReportCdnProperties.class
})
public class S3Config {

    @Bean(destroyMethod = "close")
    public S3Client s3Client(
            ReportStorageProperties properties
    ) {
        AwsBasicCredentials credentials =
                AwsBasicCredentials.create(
                        properties.accessKey(),
                        properties.secretKey()
                );

        return S3Client.builder()
                .endpointOverride(
                        URI.create(properties.endpoint())
                )
                .region(
                        Region.of(properties.region())
                )
                .credentialsProvider(
                        StaticCredentialsProvider.create(credentials)
                )
                .forcePathStyle(true)
                .build();
    }
}