import React, {
  FormEvent,
  useCallback,
  useEffect,
  useMemo,
  useState
} from 'react';

/**
 * Безопасные сведения о серверной пользовательской сессии.
 *
 * Access token и refresh token во frontend не передаются.
 */
interface AuthSession {
  username: string;
  accessTokenExpiresAt: string;
}

/**
 * Дневной агрегат работы протеза.
 */
interface DailyReport {
  reportDate: string;
  customerId: string;
  prosthesisId: string;
  prosthesisModel: string;
  region: string;
  telemetryEvents: number;
  usageSeconds: number;
  averageBatteryLevel: number;
  minimumBatteryLevel: number;
  averageTemperatureC: number;
  alertsCount: number;
  lastTelemetryAt: string;
}

/**
 * Ответ сервиса отчётов.
 */
interface ReportResponse {
  userSubject: string;
  requestedFrom: string;
  requestedTo: string;
  dataAvailableThrough: string;
  generatedAt: string;
  days: DailyReport[];
}

/**
 * Ответ API со ссылкой на сформированный отчёт.
 */
interface ReportLinkResponse {
  reportUrl: string;
  source: 'GENERATED' | 'S3_HIT';
  requestedFrom: string;
  requestedTo: string;
  dataAvailableThrough: string;
  generatedAt: string;
}

/**
 * Ошибка в формате RFC 9457 Problem Details.
 */
interface ProblemDetails {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  dataAvailableThrough?: string;
  processedThrough?: string;
  availableThrough?: string;
  [key: string]: unknown;
}

interface DefaultPeriod {
  from: string;
  to: string;
}

const REPORT_PERIOD_DAYS = 7;

/**
 * Формирует период из последних семи завершённых календарных дней.
 */
const createDefaultPeriod = (): DefaultPeriod => {
  const to = new Date();
  to.setUTCDate(to.getUTCDate() - 1);

  const from = new Date(to);
  from.setUTCDate(from.getUTCDate() - (REPORT_PERIOD_DAYS - 1));

  return {
    from: from.toISOString().slice(0, 10),
    to: to.toISOString().slice(0, 10)
  };
};

const DEFAULT_PERIOD = createDefaultPeriod();
const TODAY = new Date().toISOString().slice(0, 10);

/**
 * Безопасно читает JSON-ответ.
 */
const readJson = async <T,>(response: Response): Promise<T | null> => {
  const body = await response.text();

  if (!body) {
    return null;
  }

  try {
    return JSON.parse(body) as T;
  } catch {
    return null;
  }
};

/**
 * Форматирует дату без смещения из-за часового пояса браузера.
 */
const formatDate = (value: string): string => {
  const [year, month, day] = value.split('-');

  if (!year || !month || !day) {
    return value;
  }

  return `${day}.${month}.${year}`;
};

/**
 * Форматирует момент времени.
 *
 * ClickHouse и Java могут возвращать дробную часть секунд
 * с точностью больше миллисекунд, поэтому лишние цифры обрезаются.
 */
const formatInstant = (value: string): string => {
  if (!value) {
    return '—';
  }

  const normalizedValue = value.replace(
      /(\.\d{3})\d+(Z|[+-]\d{2}:\d{2})$/,
      '$1$2'
  );

  const date = new Date(normalizedValue);

  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return new Intl.DateTimeFormat('ru-RU', {
    dateStyle: 'short',
    timeStyle: 'medium'
  }).format(date);
};

/**
 * Форматирует продолжительность использования.
 */
const formatDuration = (seconds: number): string => {
  const safeSeconds = Math.max(0, Math.round(seconds));
  const hours = Math.floor(safeSeconds / 3600);
  const minutes = Math.floor((safeSeconds % 3600) / 60);

  if (hours === 0) {
    return `${minutes} мин`;
  }

  return `${hours} ч ${minutes} мин`;
};

const formatDecimal = (
    value: number,
    fractionDigits = 1
): string => {
  return new Intl.NumberFormat('ru-RU', {
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: fractionDigits
  }).format(value);
};

/**
 * Извлекает дату последней успешной обработки ETL
 * из расширений Problem Details.
 */
const extractAvailableThrough = (
    problem: ProblemDetails | null
): string | null => {
  if (!problem) {
    return null;
  }

  const candidates = [
    problem.dataAvailableThrough,
    problem.processedThrough,
    problem.availableThrough
  ];

  const value = candidates.find(
      candidate => typeof candidate === 'string'
  );

  return value ?? null;
};

/**
 * Страница просмотра отчёта о работе протеза.
 */
const ReportPage: React.FC = () => {
  const [session, setSession] = useState<AuthSession | null>(null);
  const [initializing, setInitializing] = useState(true);
  const [loading, setLoading] = useState(false);

  const [from, setFrom] = useState(DEFAULT_PERIOD.from);
  const [to, setTo] = useState(DEFAULT_PERIOD.to);

  const [report, setReport] = useState<ReportResponse | null>(null);
  const [availableThrough, setAvailableThrough] =
      useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  /**
   * Проверяет серверную пользовательскую сессию.
   */
  const loadSession = useCallback(async (): Promise<void> => {
    try {
      setError(null);

      const response = await fetch('/api/auth/session', {
        method: 'GET',
        credentials: 'include',
        headers: {
          Accept: 'application/json'
        }
      });

      if (response.status === 401) {
        setSession(null);
        return;
      }

      if (!response.ok) {
        throw new Error(
            `Не удалось проверить сессию: HTTP ${response.status}`
        );
      }

      const authSession = await readJson<AuthSession>(response);

      if (!authSession) {
        throw new Error('Сервис авторизации вернул некорректный ответ');
      }

      setSession(authSession);
    } catch (requestError) {
      setSession(null);
      setError(
          requestError instanceof Error
              ? requestError.message
              : 'Не удалось проверить пользовательскую сессию'
      );
    } finally {
      setInitializing(false);
    }
  }, []);

  useEffect(() => {
    void loadSession();
  }, [loadSession]);

  /**
   * Запускает Authorization Code Flow через BFF.
   */
  const login = (): void => {
    window.location.assign('/oauth2/authorization/keycloak');
  };

  /**
   * Загружает отчёт за выбранный период.
   */
  const loadReport = async (
      event: FormEvent<HTMLFormElement>
  ): Promise<void> => {
    event.preventDefault();

    if (!from || !to) {
      setError('Укажите начало и конец периода');
      return;
    }

    if (from > to) {
      setError('Дата начала периода не может быть позже даты окончания');
      return;
    }

    try {
      setLoading(true);
      setError(null);
      setReport(null);
      setAvailableThrough(null);

      const query = new URLSearchParams({
        from,
        to
      });

      const response = await fetch(`/api/reports?${query.toString()}`, {
        method: 'GET',
        credentials: 'include',
        headers: {
          Accept: 'application/json'
        }
      });

      const responseBody = await readJson<
          ReportLinkResponse | ProblemDetails
      >(response);

      if (response.status === 401) {
        setSession(null);

        throw new Error(
            'Сессия истекла. Выполните вход повторно.'
        );
      }

      if (response.status === 409) {
        const problem = responseBody as ProblemDetails | null;
        const processedThrough = extractAvailableThrough(problem);

        setAvailableThrough(processedThrough);

        throw new Error(
            problem?.detail ??
            'Выбранный период ещё не полностью обработан ETL'
        );
      }

      if (response.status === 400) {
        const problem = responseBody as ProblemDetails | null;

        throw new Error(
            problem?.detail ?? 'Некорректный период отчёта'
        );
      }

      if (response.status === 503) {
        throw new Error(
            'Сервис отчётов временно недоступен. Повторите запрос позже.'
        );
      }

      if (!response.ok) {
        const problem = responseBody as ProblemDetails | null;

        throw new Error(
            problem?.detail ??
            `Не удалось получить отчёт: HTTP ${response.status}`
        );
      }

      const reportLink = responseBody as ReportLinkResponse | null;

      if (
          !reportLink ||
          typeof reportLink.reportUrl !== 'string' ||
          reportLink.reportUrl.trim() === ''
      ) {
        throw new Error(
            'Сервис отчётов не вернул ссылку на сформированный отчёт'
        );
      }

      setAvailableThrough(reportLink.dataAvailableThrough);

      const reportFileResponse = await fetch(reportLink.reportUrl, {
        method: 'GET',
        credentials: 'include',
        headers: {
          Accept: 'application/json'
        }
      });

      if (reportFileResponse.status === 401) {
        setSession(null);

        throw new Error(
            'Сессия истекла. Выполните вход повторно.'
        );
      }

      if (!reportFileResponse.ok) {
        throw new Error(
            `Не удалось загрузить отчёт из CDN: HTTP ${reportFileResponse.status}`
        );
      }

      const reportResponse =
          await readJson<ReportResponse>(reportFileResponse);

      if (
          !reportResponse ||
          !Array.isArray(reportResponse.days)
      ) {
        throw new Error(
            'CDN вернул некорректное содержимое отчёта'
        );
      }

      setReport(reportResponse);
      setAvailableThrough(reportResponse.dataAvailableThrough);
    } catch (requestError) {
      setError(
          requestError instanceof Error
              ? requestError.message
              : 'Не удалось получить отчёт'
      );
    } finally {
      setLoading(false);
    }
  };

  /**
   * Скачивает уже полученный отчёт в формате JSON.
   */
  const downloadJson = (): void => {
    if (!report) {
      return;
    }

    const blob = new Blob(
        [JSON.stringify(report, null, 2)],
        {
          type: 'application/json;charset=utf-8'
        }
    );

    const downloadUrl = window.URL.createObjectURL(blob);
    const link = document.createElement('a');

    link.href = downloadUrl;
    link.download =
        `bionicpro-report-${report.requestedFrom}-${report.requestedTo}.json`;

    document.body.appendChild(link);
    link.click();
    link.remove();

    window.URL.revokeObjectURL(downloadUrl);
  };

  const summary = useMemo(() => {
    if (!report || report.days.length === 0) {
      return null;
    }

    const totalUsageSeconds = report.days.reduce(
        (result, day) => result + day.usageSeconds,
        0
    );

    const telemetryEvents = report.days.reduce(
        (result, day) => result + day.telemetryEvents,
        0
    );

    const alertsCount = report.days.reduce(
        (result, day) => result + day.alertsCount,
        0
    );

    const averageBatteryLevel =
        report.days.reduce(
            (result, day) => result + day.averageBatteryLevel,
            0
        ) / report.days.length;

    return {
      totalUsageSeconds,
      telemetryEvents,
      alertsCount,
      averageBatteryLevel
    };
  }, [report]);

  if (initializing) {
    return (
        <main className="flex min-h-screen items-center justify-center bg-slate-100">
          <div className="rounded-lg bg-white px-8 py-6 shadow">
            Проверка пользовательской сессии...
          </div>
        </main>
    );
  }

  if (!session) {
    return (
        <main className="flex min-h-screen items-center justify-center bg-slate-100 p-4">
          <section className="w-full max-w-md rounded-xl bg-white p-8 shadow-lg">
            <h1 className="mb-3 text-2xl font-bold text-slate-900">
              BionicPRO Reports
            </h1>

            <p className="mb-6 text-sm leading-6 text-slate-600">
              Для просмотра персонального отчёта необходимо пройти
              аутентификацию через Keycloak.
            </p>

            <button
                type="button"
                onClick={login}
                className="w-full rounded-lg bg-blue-600 px-4 py-3 font-medium text-white transition hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2"
            >
              Войти
            </button>

            {error && (
                <div
                    role="alert"
                    className="mt-5 rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-800"
                >
                  {error}
                </div>
            )}
          </section>
        </main>
    );
  }

  return (
      <main className="min-h-screen bg-slate-100 px-4 py-8">
        <div className="mx-auto max-w-7xl">
          <header className="mb-6 rounded-xl bg-white p-6 shadow-sm">
            <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-start">
              <div>
                <h1 className="text-2xl font-bold text-slate-900">
                  Отчёт о работе протеза
                </h1>

                <p className="mt-2 text-sm text-slate-600">
                  Пользователь:{' '}
                  <span className="font-medium text-slate-900">
                  {session.username}
                </span>
                </p>
              </div>

              <div className="text-sm text-slate-500">
                Access token действует до:
                <div className="mt-1 font-medium text-slate-700">
                  {formatInstant(session.accessTokenExpiresAt)}
                </div>
              </div>
            </div>
          </header>

          <section className="mb-6 rounded-xl bg-white p-6 shadow-sm">
            <form
                onSubmit={loadReport}
                className="flex flex-col gap-4 lg:flex-row lg:items-end"
            >
              <div className="flex-1">
                <label
                    htmlFor="report-from"
                    className="mb-2 block text-sm font-medium text-slate-700"
                >
                  Начало периода
                </label>

                <input
                    id="report-from"
                    type="date"
                    value={from}
                    max={to || TODAY}
                    onChange={event => setFrom(event.target.value)}
                    className="w-full rounded-lg border border-slate-300 px-3 py-2.5 text-slate-900 outline-none transition focus:border-blue-500 focus:ring-2 focus:ring-blue-200"
                />
              </div>

              <div className="flex-1">
                <label
                    htmlFor="report-to"
                    className="mb-2 block text-sm font-medium text-slate-700"
                >
                  Конец периода
                </label>

                <input
                    id="report-to"
                    type="date"
                    value={to}
                    min={from}
                    max={TODAY}
                    onChange={event => setTo(event.target.value)}
                    className="w-full rounded-lg border border-slate-300 px-3 py-2.5 text-slate-900 outline-none transition focus:border-blue-500 focus:ring-2 focus:ring-blue-200"
                />
              </div>

              <button
                  type="submit"
                  disabled={loading}
                  className="rounded-lg bg-blue-600 px-6 py-2.5 font-medium text-white transition hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
              >
                {loading ? 'Загрузка...' : 'Получить отчёт'}
              </button>

              {report && (
                  <button
                      type="button"
                      onClick={downloadJson}
                      className="rounded-lg border border-slate-300 bg-white px-6 py-2.5 font-medium text-slate-700 transition hover:bg-slate-50"
                  >
                    Скачать JSON
                  </button>
              )}
            </form>

            {availableThrough && (
                <div className="mt-4 text-sm text-slate-600">
                  Данные обработаны по:{' '}
                  <span className="font-medium text-slate-900">
                {formatDate(availableThrough)}
              </span>
                </div>
            )}

            {error && (
                <div
                    role="alert"
                    className="mt-5 rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-800"
                >
                  {error}

                  {availableThrough && (
                      <div className="mt-2">
                        Выберите дату окончания не позднее{' '}
                        <strong>{formatDate(availableThrough)}</strong>.
                      </div>
                  )}
                </div>
            )}
          </section>

          {report && summary && (
              <>
                <section className="mb-6 grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
                  <article className="rounded-xl bg-white p-5 shadow-sm">
                    <div className="text-sm text-slate-500">
                      Использование
                    </div>
                    <div className="mt-2 text-2xl font-bold text-slate-900">
                      {formatDuration(summary.totalUsageSeconds)}
                    </div>
                  </article>

                  <article className="rounded-xl bg-white p-5 shadow-sm">
                    <div className="text-sm text-slate-500">
                      События телеметрии
                    </div>
                    <div className="mt-2 text-2xl font-bold text-slate-900">
                      {summary.telemetryEvents}
                    </div>
                  </article>

                  <article className="rounded-xl bg-white p-5 shadow-sm">
                    <div className="text-sm text-slate-500">
                      Средний заряд
                    </div>
                    <div className="mt-2 text-2xl font-bold text-slate-900">
                      {formatDecimal(summary.averageBatteryLevel)} %
                    </div>
                  </article>

                  <article className="rounded-xl bg-white p-5 shadow-sm">
                    <div className="text-sm text-slate-500">
                      Предупреждения
                    </div>
                    <div className="mt-2 text-2xl font-bold text-slate-900">
                      {summary.alertsCount}
                    </div>
                  </article>
                </section>

                <section className="overflow-hidden rounded-xl bg-white shadow-sm">
                  <div className="border-b border-slate-200 p-6">
                    <div className="flex flex-col justify-between gap-3 sm:flex-row">
                      <div>
                        <h2 className="text-lg font-semibold text-slate-900">
                          Данные по дням
                        </h2>

                        <p className="mt-1 text-sm text-slate-500">
                          {formatDate(report.requestedFrom)} —{' '}
                          {formatDate(report.requestedTo)}
                        </p>
                      </div>

                      <div className="text-sm text-slate-500">
                        Сформирован:
                        <div className="font-medium text-slate-700">
                          {formatInstant(report.generatedAt)}
                        </div>
                      </div>
                    </div>
                  </div>

                  <div className="overflow-x-auto">
                    <table className="min-w-full divide-y divide-slate-200">
                      <thead className="bg-slate-50">
                      <tr>
                        <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wide text-slate-600">
                          Дата
                        </th>
                        <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wide text-slate-600">
                          Протез
                        </th>
                        <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wide text-slate-600">
                          Регион
                        </th>
                        <th className="px-4 py-3 text-right text-xs font-semibold uppercase tracking-wide text-slate-600">
                          События
                        </th>
                        <th className="px-4 py-3 text-right text-xs font-semibold uppercase tracking-wide text-slate-600">
                          Использование
                        </th>
                        <th className="px-4 py-3 text-right text-xs font-semibold uppercase tracking-wide text-slate-600">
                          Средний заряд
                        </th>
                        <th className="px-4 py-3 text-right text-xs font-semibold uppercase tracking-wide text-slate-600">
                          Мин. заряд
                        </th>
                        <th className="px-4 py-3 text-right text-xs font-semibold uppercase tracking-wide text-slate-600">
                          Температура
                        </th>
                        <th className="px-4 py-3 text-right text-xs font-semibold uppercase tracking-wide text-slate-600">
                          Предупреждения
                        </th>
                        <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wide text-slate-600">
                          Последняя телеметрия
                        </th>
                      </tr>
                      </thead>

                      <tbody className="divide-y divide-slate-100 bg-white">
                      {report.days.map(day => (
                          <tr
                              key={`${day.reportDate}-${day.prosthesisId}`}
                              className="hover:bg-slate-50"
                          >
                            <td className="whitespace-nowrap px-4 py-3 text-sm font-medium text-slate-900">
                              {formatDate(day.reportDate)}
                            </td>

                            <td className="whitespace-nowrap px-4 py-3 text-sm text-slate-700">
                              {day.prosthesisModel}
                            </td>

                            <td className="whitespace-nowrap px-4 py-3 text-sm text-slate-700">
                              {day.region}
                            </td>

                            <td className="whitespace-nowrap px-4 py-3 text-right text-sm text-slate-700">
                              {day.telemetryEvents}
                            </td>

                            <td className="whitespace-nowrap px-4 py-3 text-right text-sm text-slate-700">
                              {formatDuration(day.usageSeconds)}
                            </td>

                            <td className="whitespace-nowrap px-4 py-3 text-right text-sm text-slate-700">
                              {formatDecimal(day.averageBatteryLevel)} %
                            </td>

                            <td className="whitespace-nowrap px-4 py-3 text-right text-sm text-slate-700">
                              {formatDecimal(day.minimumBatteryLevel)} %
                            </td>

                            <td className="whitespace-nowrap px-4 py-3 text-right text-sm text-slate-700">
                              {formatDecimal(day.averageTemperatureC)} °C
                            </td>

                            <td className="whitespace-nowrap px-4 py-3 text-right text-sm text-slate-700">
                              {day.alertsCount}
                            </td>

                            <td className="whitespace-nowrap px-4 py-3 text-sm text-slate-700">
                              {formatInstant(day.lastTelemetryAt)}
                            </td>
                          </tr>
                      ))}
                      </tbody>
                    </table>
                  </div>

                  {report.days.length === 0 && (
                      <div className="p-8 text-center text-slate-500">
                        За выбранный период данные отсутствуют.
                      </div>
                  )}
                </section>
              </>
          )}
        </div>
      </main>
  );
};

export default ReportPage;