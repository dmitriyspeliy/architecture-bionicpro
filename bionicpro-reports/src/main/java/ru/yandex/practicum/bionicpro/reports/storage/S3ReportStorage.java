package ru.yandex.practicum.bionicpro.reports.storage;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * S3-реализация хранилища сформированных отчётов.
 */
@Component
public class S3ReportStorage implements ReportStorage {

    private static final String GENERATED_AT_METADATA =
            "generated-at";

    private static final String REPORT_CACHE_CONTROL =
            "public, max-age=31536000, immutable";

    private final S3Client s3Client;
    private final ReportStorageProperties properties;

    public S3ReportStorage(
            S3Client s3Client,
            ReportStorageProperties properties
    ) {
        this.s3Client = s3Client;
        this.properties = properties;
    }

    @Override
    public Optional<StoredReportMetadata> find(
            String objectKey
    ) {
        HeadObjectRequest request =
                HeadObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(objectKey)
                        .build();

        try {
            HeadObjectResponse response =
                    s3Client.headObject(request);

            Instant generatedAt = extractGeneratedAt(response);

            return Optional.of(
                    new StoredReportMetadata(generatedAt)
            );
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                return Optional.empty();
            }

            throw new ReportStorageException(
                    "Failed to check report object in S3",
                    exception
            );
        }
    }

    @Override
    public void save(
            String objectKey,
            byte[] content,
            Instant generatedAt
    ) {
        PutObjectRequest request =
                PutObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(objectKey)
                        .contentType("application/json")
                        .cacheControl(REPORT_CACHE_CONTROL)
                        .metadata(
                                Map.of(
                                        GENERATED_AT_METADATA,
                                        generatedAt.toString()
                                )
                        )
                        .build();

        try {
            s3Client.putObject(
                    request,
                    RequestBody.fromBytes(content)
            );
        } catch (S3Exception exception) {
            throw new ReportStorageException(
                    "Failed to save generated report to S3",
                    exception
            );
        }
    }

    private Instant extractGeneratedAt(
            HeadObjectResponse response
    ) {
        String value =
                response.metadata().get(GENERATED_AT_METADATA);

        if (value != null) {
            try {
                return Instant.parse(value);
            } catch (DateTimeException ignored) {
                // Используем lastModified ниже.
            }
        }

        if (response.lastModified() != null) {
            return response.lastModified();
        }

        return Instant.EPOCH;
    }
}