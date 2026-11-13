package com.tanmay.boardwallet.service;

import com.tanmay.boardwallet.entity.RefreshToken;
import com.tanmay.boardwallet.entity.User;
import com.tanmay.boardwallet.repository.RefreshTokenRepository;
import com.tanmay.boardwallet.repository.UserRepository;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class RefreshTokenService {

    private static final Duration SESSION_LIFETIME = Duration.ofDays(7);

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenGenerator refreshTokenGenerator;

    public RefreshTokenService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            RefreshTokenGenerator refreshTokenGenerator) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshTokenGenerator = refreshTokenGenerator;
    }

    @Transactional
    public String issueForLogin(String authenticatedUsername) {
        User user = userRepository.findByUsername(authenticatedUsername)
                .filter(User::getEnabled)
                .orElseThrow(() -> new BadCredentialsException("Account unavailable"));

        Instant now = Instant.now();
        String rawToken = refreshTokenGenerator.generate();

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUser(user);
        refreshToken.setFamilyId(UUID.randomUUID());
        refreshToken.setTokenHash(refreshTokenGenerator.hash(rawToken));
        refreshToken.setCreatedAt(now);
        refreshToken.setExpiresAt(now.plus(SESSION_LIFETIME));

        refreshTokenRepository.save(refreshToken);

        return rawToken;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<RotatedRefreshToken> rotate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }

        String tokenHash = refreshTokenGenerator.hash(rawToken);
        Optional<UUID> userId = refreshTokenRepository.findUserIdByTokenHash(tokenHash);

        if (userId.isEmpty()) {
            return Optional.empty();
        }

        Optional<User> lockedUser = userRepository.findByIdForUpdate(userId.get())
                .filter(User::getEnabled);

        if (lockedUser.isEmpty()) {
            return Optional.empty();
        }

        Optional<RefreshToken> storedToken = refreshTokenRepository.findByTokenHash(tokenHash);

        if (storedToken.isEmpty()) {
            return Optional.empty();
        }

        RefreshToken current = storedToken.get();
        Instant now = Instant.now();

        if (current.getRevokedAt() != null || !current.getExpiresAt().isAfter(now)) {
            return Optional.empty();
        }

        if (current.getUsedAt() != null) {
            revokeFamily(current.getFamilyId(), now);
            return Optional.empty();
        }

        String replacementRawToken = refreshTokenGenerator.generate();

        RefreshToken replacement = new RefreshToken();
        replacement.setUser(lockedUser.get());
        replacement.setFamilyId(current.getFamilyId());
        replacement.setTokenHash(refreshTokenGenerator.hash(replacementRawToken));
        replacement.setCreatedAt(now);
        replacement.setExpiresAt(current.getExpiresAt());

        current.setUsedAt(now);
        refreshTokenRepository.save(replacement);

        return Optional.of(new RotatedRefreshToken(
                lockedUser.get().getUsername(),
                replacementRawToken
        ));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void revokeForLogout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }

        String tokenHash = refreshTokenGenerator.hash(rawToken);
        Optional<UUID> userId = refreshTokenRepository.findUserIdByTokenHash(tokenHash);

        if (userId.isEmpty() || userRepository.findByIdForUpdate(userId.get()).isEmpty()) {
            return;
        }

        refreshTokenRepository.findByTokenHash(tokenHash)
                .ifPresent(token -> revokeFamily(token.getFamilyId(), Instant.now()));
    }

    private void revokeFamily(UUID familyId, Instant revokedAt) {
        for (RefreshToken token : refreshTokenRepository.findAllByFamilyId(familyId)) {
            if (token.getRevokedAt() == null) {
                token.setRevokedAt(revokedAt);
            }
        }
    }

    public static final class RotatedRefreshToken {

        private final String username;
        private final String rawToken;

        private RotatedRefreshToken(String username, String rawToken) {
            this.username = username;
            this.rawToken = rawToken;
        }

        public String getUsername() {
            return username;
        }

        public String getRawToken() {
            return rawToken;
        }
    }
}