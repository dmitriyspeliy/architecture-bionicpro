import React, { useCallback, useEffect, useState } from 'react';

/**
 * Безопасные сведения о серверной пользовательской сессии.
 */
interface AuthSession {
  /** Имя аутентифицированного пользователя. */
  username: string;

  /** Время окончания действия текущего access token. */
  accessTokenExpiresAt: string;
}

/**
 * Страница получения отчёта о работе протеза.
 */
const ReportPage: React.FC = () => {
  const [session, setSession] = useState<AuthSession | null>(null);
  const [initializing, setInitializing] = useState(true);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  /**
   * Получает состояние серверной сессии.
   *
   * Access token и refresh token frontend-приложению не передаются.
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

      const authSession = await response.json() as AuthSession;
      setSession(authSession);
    } catch (requestError) {
      setSession(null);
      setError(
          requestError instanceof Error
              ? requestError.message
              : 'Не удалось проверить сессию'
      );
    } finally {
      setInitializing(false);
    }
  }, []);

  useEffect(() => {
    void loadSession();
  }, [loadSession]);

  /**
   * Запускает Authorization Code Flow через backend.
   */
  const login = (): void => {
    window.location.assign('/oauth2/authorization/keycloak');
  };

  /**
   * Запрашивает отчёт через серверную сессию.
   */
  const downloadReport = async (): Promise<void> => {
    try {
      setLoading(true);
      setError(null);

      const response = await fetch('/api/reports', {
        method: 'GET',
        credentials: 'include'
      });

      if (response.status === 401) {
        setSession(null);
        throw new Error('Сессия истекла. Выполните вход повторно.');
      }

      if (!response.ok) {
        throw new Error(
            `Не удалось получить отчёт: HTTP ${response.status}`
        );
      }

      const report = await response.blob();
      const downloadUrl = window.URL.createObjectURL(report);
      const link = document.createElement('a');

      link.href = downloadUrl;
      link.download = 'prosthesis-report';
      document.body.appendChild(link);
      link.click();
      link.remove();

      window.URL.revokeObjectURL(downloadUrl);
    } catch (requestError) {
      setError(
          requestError instanceof Error
              ? requestError.message
              : 'Не удалось скачать отчёт'
      );
    } finally {
      setLoading(false);
    }
  };

  if (initializing) {
    return (
        <div className="flex items-center justify-center min-h-screen">
          Проверка сессии...
        </div>
    );
  }

  if (!session) {
    return (
        <div className="flex flex-col items-center justify-center min-h-screen bg-gray-100">
          <button
              type="button"
              onClick={login}
              className="px-4 py-2 text-white bg-blue-500 rounded hover:bg-blue-600"
          >
            Войти
          </button>

          {error && (
              <div className="mt-4 p-4 text-red-700 bg-red-100 rounded">
                {error}
              </div>
          )}
        </div>
    );
  }

  return (
      <div className="flex flex-col items-center justify-center min-h-screen bg-gray-100">
        <div className="p-8 bg-white rounded-lg shadow-md">
          <h1 className="mb-2 text-2xl font-bold">
            Отчёты о работе протеза
          </h1>

          <div className="mb-6 text-gray-600">
            Пользователь: {session.username}
          </div>

          <button
              type="button"
              onClick={downloadReport}
              disabled={loading}
              className={`px-4 py-2 text-white bg-blue-500 rounded hover:bg-blue-600 ${
                  loading ? 'opacity-50 cursor-not-allowed' : ''
              }`}
          >
            {loading ? 'Формирование отчёта...' : 'Скачать отчёт'}
          </button>

          {error && (
              <div className="mt-4 p-4 text-red-700 bg-red-100 rounded">
                {error}
              </div>
          )}
        </div>
      </div>
  );
};

export default ReportPage;