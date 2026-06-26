import React from 'react';
import ReportPage from './components/ReportPage';

/**
 * Корневой компонент frontend-приложения.
 *
 * OAuth2-токены обрабатываются только backend-сервисом bionicpro-auth.
 * Frontend использует серверную сессию через HttpOnly cookie.
 */
const App: React.FC = () => {
    return (
        <div className="App">
            <ReportPage />
        </div>
    );
};

export default App;