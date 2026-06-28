package ru.yandex.practicum.bionicpro.auth.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Конфигурация HTTP-клиента сервиса отчётов.
 */
@Configuration
public class ReportsClientConfig {

    /**
     * Создаёт клиент для обращения к bionicpro-reports.
     *
     * @param builder стандартный Spring RestClient builder
     * @param baseUrl базовый адрес сервиса отчётов
     * @return настроенный HTTP-клиент
     */
    @Bean
    public RestClient reportsRestClient(
            RestClient.Builder builder,
            @Value("${bionicpro.services.reports.base-url}")
            String baseUrl
    ) {
        return builder
                .baseUrl(baseUrl)
                .build();
    }
}