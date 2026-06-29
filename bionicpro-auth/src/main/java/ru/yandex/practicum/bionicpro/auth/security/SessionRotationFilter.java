package ru.yandex.practicum.bionicpro.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Перевыпускает идентификатор серверной сессии при обращении
 * аутентифицированного пользователя к защищённому API.
 */
public final class SessionRotationFilter extends OncePerRequestFilter {

    /** Префикс защищённых backend-методов. */
    private static final String API_PATH_PREFIX = "/api/";

    /** Заголовок, подтверждающий ротацию сессии без раскрытия session ID. */
    private static final String ROTATION_HEADER = "X-Session-Rotated";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(API_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (isAuthenticated(authentication) && request.getSession(false) != null) {
            request.changeSessionId();

            // Сам session ID передаётся только через HttpOnly cookie.
            response.setHeader(ROTATION_HEADER, Boolean.TRUE.toString());
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Проверяет наличие реальной пользовательской аутентификации.
     *
     * @param authentication текущая аутентификация
     * @return true, если пользователь аутентифицирован
     */
    private boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}