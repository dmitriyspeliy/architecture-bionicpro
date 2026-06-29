package ru.yandex.practicum.bionicpro.reports.storage;

import org.springframework.stereotype.Component;
import ru.yandex.practicum.bionicpro.reports.report.EtlWatermark;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.util.HexFormat;

/**
 * Формирует детерминированный и непредсказуемый ключ объекта.
 */
@Component
public class ReportObjectKeyFactory {

    private static final String HMAC_ALGORITHM =
            "HmacSHA256";

    private final SecretKeySpec secretKey;

    public ReportObjectKeyFactory(
            ReportStorageProperties properties
    ) {
        this.secretKey = new SecretKeySpec(
                properties.objectKeySecret()
                        .getBytes(StandardCharsets.UTF_8),
                HMAC_ALGORITHM
        );
    }

    public String create(
            String userSubject,
            LocalDate from,
            LocalDate to,
            EtlWatermark watermark,
            long crmSourceLsn
    ) {
        String userPath = hmac(userSubject);

        String periodPath =
                from + "_" + to;

        String reportVersion =
                "etl-%d_crm-%d".formatted(
                        watermark.updatedAt().toEpochMilli(),
                        crmSourceLsn
                );

        return "reports/v1/%s/%s/%s/report.json"
                .formatted(
                        userPath,
                        periodPath,
                        reportVersion
                );
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(secretKey);

            byte[] digest = mac.doFinal(
                    value.getBytes(StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "Unable to calculate report object key",
                    exception
            );
        }
    }
}