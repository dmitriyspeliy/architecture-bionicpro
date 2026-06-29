# BionicPRO — Sprint 9

Проектная работа по развитию архитектуры BionicPRO.

В проекте реализованы:

* backend-сервис авторизации с OAuth 2.0 Authorization Code Flow и PKCE;
* серверные пользовательские сессии в Redis;
* интеграция Keycloak с LDAP и Яндекс ID;
* сервис формирования отчётов;
* ETL-процесс на Apache Airflow;
* витрины в ClickHouse;
* хранение отчётов в MinIO/S3;
* кеширование отчётов через Nginx;
* CDC-поток CRM через Debezium, Kafka и ClickHouse KafkaEngine.

## Требования

Для локального запуска необходимы:

* Docker Desktop;
* Docker Compose;
* JDK 21;
* PowerShell для приведённых ниже команд.

Java-сервисы запускаются локально через Maven Wrapper.

Инфраструктурные компоненты запускаются через Docker Compose.

## 1. Подготовка переменных окружения

Файл `.env` намеренно не хранится в Git, поскольку содержит локальные пароли и OAuth-секреты.

Создайте `.env` из шаблона:

```powershell
Copy-Item .env.example .env
```

Заполните значения в созданном `.env`.

Необходимо заменить все значения:

```text
change-me
replace-with-generated-fernet-key
replace-with-random-secret
replace-with-valid-kafka-cluster-id
```

Для входа через Яндекс ID необходимо указать параметры собственного OAuth-приложения:

```dotenv
YANDEX_CLIENT_ID=...
YANDEX_CLIENT_SECRET=...
```

Файл `.env` не должен добавляться в Git.

## 2. Загрузка `.env` в PowerShell

Docker Compose читает `.env` автоматически.

Java-приложения, запускаемые локально, необходимо запускать в PowerShell-сессии, в которую переменные из `.env` предварительно загружены.

В каждом терминале выполните:

```powershell
Get-Content .env |
ForEach-Object {
    $line = $_.Trim()

    if (
        $line -ne "" -and
        -not $line.StartsWith("#")
    ) {
        $name, $value = $line -split "=", 2

        [Environment]::SetEnvironmentVariable(
            $name.Trim(),
            $value.Trim(),
            "Process"
        )
    }
}
```

## 3. Запуск инфраструктуры

Из корня проекта выполните:

```powershell
docker compose --env-file .env up -d --build
```

Проверьте состояние контейнеров:

```powershell
docker compose ps -a
```

Одноразовые контейнеры могут завершиться со статусом `Exited (0)`:

* `airflow_init`;
* `crm_cdc_init`;
* `minio_init`.

Это является штатным поведением.

Контейнеры PostgreSQL, ClickHouse, Kafka, Kafka Connect, Keycloak, Redis, MinIO, Airflow и Nginx должны находиться в состоянии `Up` или `healthy`.

## 4. Запуск сервиса отчётов

Откройте отдельный PowerShell-терминал.

Перейдите в корень проекта и загрузите переменные из `.env`, как описано выше.

Запустите сервис:

```powershell
.\bionicpro-reports\mvnw.cmd `
  -f .\bionicpro-reports\pom.xml `
  spring-boot:run
```

Сервис будет доступен по адресу:

```text
http://localhost:8082
```

Проверка health endpoint:

```powershell
curl.exe http://localhost:8082/actuator/health
```

## 5. Запуск сервиса авторизации

Откройте ещё один PowerShell-терминал.

Перейдите в корень проекта и повторно загрузите переменные из `.env`.

Запустите сервис:

```powershell
.\bionicpro-auth\mvnw.cmd `
  -f .\bionicpro-auth\pom.xml `
  spring-boot:run
```

Сервис будет доступен по адресу:

```text
http://localhost:8081
```

Проверка health endpoint:

```powershell
curl.exe http://localhost:8081/actuator/health
```

## 6. Открытие frontend

Откройте в браузере:

```text
http://localhost:3000
```

Frontend не взаимодействует с Keycloak напрямую.

Authorization Code Flow запускается через backend-сервис `bionicpro-auth`.

Frontend получает только серверную session cookie. Access token и refresh token во frontend не передаются.

## 7. Получение отчёта

После аутентификации выберите период отчёта.

Запрос выполняется через:

```text
GET /api/reports?from=YYYY-MM-DD&to=YYYY-MM-DD
```

Сервис возвращает ссылку на сформированный JSON-отчёт:

```json
{
  "reportUrl": "http://localhost:3000/cdn/reports/...",
  "source": "GENERATED"
}
```

После этого frontend загружает сам JSON-файл через CDN.

Повторный запрос для той же версии данных возвращает:

```json
{
  "source": "S3_HIT"
}
```

## 8. Основные адреса

| Компонент         | Адрес                  |
| ----------------- | ---------------------- |
| Frontend          | http://localhost:3000  |
| Keycloak          | http://localhost:8080  |
| bionicpro-auth    | http://localhost:8081  |
| bionicpro-reports | http://localhost:8082  |
| Kafka Connect     | http://localhost:8083  |
| Airflow           | http://localhost:8088  |
| MinIO S3          | http://localhost:9010  |
| MinIO Console     | http://localhost:9011  |
| ClickHouse HTTP   | http://localhost:18123 |

## 9. Изменение локальных портов

Если порт `18123` занят, измените в `.env`:

```dotenv
CLICKHOUSE_HTTP_PORT=28123
CLICKHOUSE_PORT=28123
```

После изменения пересоздайте ClickHouse:

```powershell
docker compose up -d --force-recreate clickhouse
```

И перезапустите `bionicpro-reports`.

## 10. Остановка проекта

Остановить контейнеры без удаления данных:

```powershell
docker compose down
```

Остановить контейнеры и удалить локальные Docker volumes:

```powershell
docker compose down -v
```

Удаление volumes приведёт к удалению локальных данных PostgreSQL, ClickHouse, Kafka и MinIO.

```
```

