package ru.yandex.practicum.bionicpro.reports.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import ru.yandex.practicum.bionicpro.reports.storage.ReportCdnProperties;
import ru.yandex.practicum.bionicpro.reports.storage.ReportObjectKeyFactory;
import ru.yandex.practicum.bionicpro.reports.storage.ReportStorage;
import ru.yandex.practicum.bionicpro.reports.storage.StoredReportMetadata;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.IntStream;

/**
 * Формирует пользовательские отчёты и сохраняет их в S3.
 */
@Service
public class ReportService {

    private static final String SUCCESS_STATUS = "SUCCESS";
    private static final long MAX_REPORT_PERIOD_DAYS = 366;

    private static final Duration WATERMARK_CACHE_TTL =
            Duration.ofSeconds(30);

    private static final int GENERATION_LOCK_COUNT = 64;

    private final ReportRepository repository;
    private final ReportStorage reportStorage;
    private final ReportObjectKeyFactory objectKeyFactory;
    private final ReportCdnProperties cdnProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private final Object watermarkMonitor = new Object();

    private final ReentrantLock[] generationLocks;

    private volatile CachedWatermark cachedWatermark;

    public ReportService(
            ReportRepository repository,
            ReportStorage reportStorage,
            ReportObjectKeyFactory objectKeyFactory,
            ReportCdnProperties cdnProperties,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.repository = repository;
        this.reportStorage = reportStorage;
        this.objectKeyFactory = objectKeyFactory;
        this.cdnProperties = cdnProperties;
        this.objectMapper = objectMapper;
        this.clock = clock;

        this.generationLocks = IntStream
                .range(0, GENERATION_LOCK_COUNT)
                .mapToObj(index -> new ReentrantLock())
                .toArray(ReentrantLock[]::new);
    }

    /**
     * Возвращает CDN-ссылку на отчёт текущего пользователя.
     */
    public ReportLinkResponse getReport(
            String userSubject,
            LocalDate from,
            LocalDate to
    ) {
        validateUserSubject(userSubject);
        validatePeriod(from, to);

        EtlWatermark watermark = getLatestWatermark();

        if (!SUCCESS_STATUS.equals(watermark.status())) {
            throw new ReportDataUnavailableException(
                    "Report data is not ready. ETL status: "
                            + watermark.status()
            );
        }

        LocalDate availableThrough =
                watermark.processedUntil()
                        .atZone(ZoneOffset.UTC)
                        .toLocalDate()
                        .minusDays(1);

        if (to.isAfter(availableThrough)) {
            throw new ReportPeriodNotProcessedException(
                    to,
                    availableThrough
            );
        }

        long crmSourceLsn =
                repository.findCrmSourceLsn(userSubject);

        String objectKey = objectKeyFactory.create(
                userSubject,
                from,
                to,
                watermark,
                crmSourceLsn
        );

        Optional<StoredReportMetadata> storedReport =
                reportStorage.find(objectKey);

        if (storedReport.isPresent()) {
            return createLinkResponse(
                    objectKey,
                    ReportLinkSource.S3_HIT,
                    from,
                    to,
                    availableThrough,
                    storedReport.get().generatedAt()
            );
        }

        ReentrantLock generationLock =
                lockFor(objectKey);

        generationLock.lock();

        try {
            /*
             * После получения lock повторяем HEAD:
             * другой запрос мог уже сформировать этот отчёт.
             */
            storedReport = reportStorage.find(objectKey);

            if (storedReport.isPresent()) {
                return createLinkResponse(
                        objectKey,
                        ReportLinkSource.S3_HIT,
                        from,
                        to,
                        availableThrough,
                        storedReport.get().generatedAt()
                );
            }

            List<DailyReport> reports =
                    repository.findReports(
                            userSubject,
                            from,
                            to
                    );

            Instant generatedAt = Instant.now(clock);

            ReportResponse report = new ReportResponse(
                    userSubject,
                    from,
                    to,
                    availableThrough,
                    generatedAt,
                    List.copyOf(reports)
            );

            byte[] content = serialize(report);

            reportStorage.save(
                    objectKey,
                    content,
                    generatedAt
            );

            return createLinkResponse(
                    objectKey,
                    ReportLinkSource.GENERATED,
                    from,
                    to,
                    availableThrough,
                    generatedAt
            );
        } finally {
            generationLock.unlock();
        }
    }

    private EtlWatermark getLatestWatermark() {
        Instant now = Instant.now(clock);
        CachedWatermark current = cachedWatermark;

        if (
                current != null
                        && now.isBefore(current.expiresAt())
        ) {
            return current.watermark();
        }

        synchronized (watermarkMonitor) {
            current = cachedWatermark;
            now = Instant.now(clock);

            if (
                    current != null
                            && now.isBefore(current.expiresAt())
            ) {
                return current.watermark();
            }

            EtlWatermark loaded =
                    repository.findLatestWatermark()
                            .orElseThrow(() ->
                                    new ReportDataUnavailableException(
                                            "ETL watermark was not found"
                                    )
                            );

            cachedWatermark = new CachedWatermark(
                    loaded,
                    now.plus(WATERMARK_CACHE_TTL)
            );

            return loaded;
        }
    }

    private byte[] serialize(ReportResponse report) {
        try {
            return objectMapper.writeValueAsBytes(report);
        } catch (JsonProcessingException exception) {
            throw new ReportDataUnavailableException(
                    "Failed to serialize generated report"
            );
        }
    }

    private ReportLinkResponse createLinkResponse(
            String objectKey,
            ReportLinkSource source,
            LocalDate from,
            LocalDate to,
            LocalDate availableThrough,
            Instant generatedAt
    ) {
        return new ReportLinkResponse(
                createCdnUrl(objectKey),
                source,
                from,
                to,
                availableThrough,
                generatedAt
        );
    }

    private String createCdnUrl(String objectKey) {
        String baseUrl = cdnProperties.baseUrl()
                .replaceAll("/+$", "");

        return baseUrl + "/" + objectKey;
    }

    private ReentrantLock lockFor(String objectKey) {
        int index = Math.floorMod(
                objectKey.hashCode(),
                generationLocks.length
        );

        return generationLocks[index];
    }

    private void validateUserSubject(String userSubject) {
        if (userSubject == null || userSubject.isBlank()) {
            throw new ReportDataUnavailableException(
                    "Authenticated user subject is missing"
            );
        }
    }

    private void validatePeriod(
            LocalDate from,
            LocalDate to
    ) {
        if (from == null || to == null) {
            throw new InvalidReportPeriodException(
                    "Both from and to dates are required"
            );
        }

        if (from.isAfter(to)) {
            throw new InvalidReportPeriodException(
                    "The from date must not be after the to date"
            );
        }

        long periodDays =
                ChronoUnit.DAYS.between(from, to) + 1;

        if (periodDays > MAX_REPORT_PERIOD_DAYS) {
            throw new InvalidReportPeriodException(
                    "The report period must not exceed "
                            + MAX_REPORT_PERIOD_DAYS
                            + " days"
            );
        }
    }

    private record CachedWatermark(
            EtlWatermark watermark,
            Instant expiresAt
    ) {
    }
}