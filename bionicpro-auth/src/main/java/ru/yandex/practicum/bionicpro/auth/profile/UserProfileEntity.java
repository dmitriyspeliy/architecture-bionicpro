package ru.yandex.practicum.bionicpro.auth.profile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_profile")
public class UserProfileEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 255)
    private String issuer;

    @Column(nullable = false, length = 255)
    private String subject;

    @Column(nullable = false, length = 255)
    private String username;

    @Column(length = 320)
    private String email;

    @Column(name = "first_name", length = 255)
    private String firstName;

    @Column(name = "last_name", length = 255)
    private String lastName;

    @Column(name = "identity_provider", length = 100)
    private String identityProvider;

    @Column(name = "consent_granted_at")
    private Instant consentGrantedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserProfileEntity() {
    }

    public static UserProfileEntity create(
            String issuer,
            String subject,
            String username,
            String email,
            String firstName,
            String lastName,
            String identityProvider,
            Instant consentGrantedAt,
            Instant now
    ) {
        UserProfileEntity entity = new UserProfileEntity();

        entity.id = UUID.randomUUID();
        entity.issuer = issuer;
        entity.subject = subject;
        entity.username = username;
        entity.email = email;
        entity.firstName = firstName;
        entity.lastName = lastName;
        entity.identityProvider = identityProvider;
        entity.consentGrantedAt = consentGrantedAt;
        entity.createdAt = now;
        entity.updatedAt = now;

        return entity;
    }

    public void update(
            String username,
            String email,
            String firstName,
            String lastName,
            String identityProvider,
            Instant consentGrantedAt,
            Instant now
    ) {
        this.username = username;
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.identityProvider = identityProvider;
        this.updatedAt = now;

        /*
         * Фиксируем первое согласие пользователя.
         * При последующих входах дата не перезаписывается.
         */
        if (this.consentGrantedAt == null && consentGrantedAt != null) {
            this.consentGrantedAt = consentGrantedAt;
        }
    }
}