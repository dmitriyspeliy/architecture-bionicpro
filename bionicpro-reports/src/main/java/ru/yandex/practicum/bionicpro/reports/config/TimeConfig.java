package ru.yandex.practicum.bionicpro.reports.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Конфигурация системного времени приложения.
 */
@Configuration
public class TimeConfig {

    /**
     * Возвращает часы в UTC.
     *
     * @return системные часы UTC
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}