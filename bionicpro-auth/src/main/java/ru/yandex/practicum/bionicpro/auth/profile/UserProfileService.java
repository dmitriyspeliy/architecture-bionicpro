package ru.yandex.practicum.bionicpro.auth.profile;

import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class UserProfileService {

    private static final String YANDEX_PROVIDER = "yandex";
    private static final String DEFAULT_PROVIDER = "keycloak";

    private final UserProfileRepository repository;

    public UserProfileService(UserProfileRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void upsert(OidcUser user) {
        Instant now = Instant.now();

        String issuer = user.getIdToken().getIssuer().toString();
        String subject = user.getSubject();

        String username = firstNotBlank(
                user.getPreferredUsername(),
                user.getEmail(),
                subject
        );

        String identityProvider = firstNotBlank(
                user.getClaimAsString("identity_provider"),
                DEFAULT_PROVIDER
        );

        Instant consentGrantedAt =
                YANDEX_PROVIDER.equals(identityProvider) ? now : null;

        repository.findByIssuerAndSubject(issuer, subject)
                .ifPresentOrElse(
                        existing -> existing.update(
                                username,
                                user.getEmail(),
                                user.getGivenName(),
                                user.getFamilyName(),
                                identityProvider,
                                consentGrantedAt,
                                now
                        ),
                        () -> repository.save(
                                UserProfileEntity.create(
                                        issuer,
                                        subject,
                                        username,
                                        user.getEmail(),
                                        user.getGivenName(),
                                        user.getFamilyName(),
                                        identityProvider,
                                        consentGrantedAt,
                                        now
                                )
                        )
                );
    }

    private String firstNotBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }

        throw new IllegalArgumentException(
                "At least one non-blank value is required"
        );
    }
}