package com.tanmay.boardwallet.controller;

import com.tanmay.boardwallet.dto.login.LoginRequestDTO;
import com.tanmay.boardwallet.dto.login.LoginResponseDTO;
import com.tanmay.boardwallet.dto.registration.RegistrationRequestDTO;
import com.tanmay.boardwallet.dto.registration.RegistrationResponseDTO;
import com.tanmay.boardwallet.service.AuthService;
import com.tanmay.boardwallet.service.JwtService;
import com.tanmay.boardwallet.service.RefreshTokenService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    public AuthController(
            AuthService authService,
            AuthenticationManager authenticationManager,
            JwtService jwtService,
            RefreshTokenService refreshTokenService) {
        this.authService = authService;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
    }

    @PostMapping("/register")
    public ResponseEntity<RegistrationResponseDTO> register(@Valid @RequestBody RegistrationRequestDTO registrationRequestDTO) {
        RegistrationResponseDTO response =  authService.registration(registrationRequestDTO);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponseDTO> login(
            @Valid @RequestBody LoginRequestDTO loginRequestDTO) {

        Authentication authenticationRequest =
                UsernamePasswordAuthenticationToken.unauthenticated(
                        loginRequestDTO.getUsername(),
                        loginRequestDTO.getPassword()
                );

        Authentication authentication =
                authenticationManager.authenticate(authenticationRequest);

        String accessToken = jwtService.generateToken(authentication);
        String refreshToken = refreshTokenService.issueForLogin(authentication.getName());

        ResponseCookie refreshCookie = ResponseCookie.from("refresh_token", refreshToken)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/api/auth")
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .cacheControl(CacheControl.noStore())
                .body(new LoginResponseDTO(accessToken));
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginResponseDTO> refresh(
            @RequestHeader(name = "X-BoardWallet-Refresh", required = false) String refreshHeader,
            @CookieValue(name = "refresh_token", required = false) String refreshToken) {

        if (!"1".equals(refreshHeader)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .cacheControl(CacheControl.noStore())
                    .build();
        }

        return refreshTokenService.rotate(refreshToken)
                .map(rotated -> tokenResponse(
                        jwtService.generateToken(rotated.getUsername()),
                        rotated.getRawToken()
                ))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .cacheControl(CacheControl.noStore())
                        .build());
    }

    private ResponseEntity<LoginResponseDTO> tokenResponse(
            String accessToken, String refreshToken) {

        ResponseCookie refreshCookie = ResponseCookie.from("refresh_token", refreshToken)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/api/auth")
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .cacheControl(CacheControl.noStore())
                .body(new LoginResponseDTO(accessToken));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(name = "X-BoardWallet-Refresh", required = false) String refreshHeader,
            @CookieValue(name = "refresh_token", required = false) String refreshToken) {

        if (!"1".equals(refreshHeader)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .cacheControl(CacheControl.noStore())
                    .build();
        }

        refreshTokenService.revokeForLogout(refreshToken);

        ResponseCookie clearedCookie = ResponseCookie.from("refresh_token", "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(0)
                .build();

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearedCookie.toString())
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @GetMapping("/me")
    public String currentUser(Authentication authentication) {
        return authentication.getName();
    }
}
