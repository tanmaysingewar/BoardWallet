package com.tanmay.boardwallet.controller;

import com.tanmay.boardwallet.dto.login.LoginRequestDTO;
import com.tanmay.boardwallet.dto.login.LoginResponseDTO;
import com.tanmay.boardwallet.dto.registration.RegistrationRequestDTO;
import com.tanmay.boardwallet.dto.registration.RegistrationResponseDTO;
import com.tanmay.boardwallet.service.AuthService;
import com.tanmay.boardwallet.service.JwtService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
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

    public AuthController(AuthService authService, AuthenticationManager authenticationManager, JwtService jwtService) {
        this.authService = authService;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
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

        String token = jwtService.generateToken(authentication);

        return ResponseEntity.ok(new LoginResponseDTO(token));
    }

    @GetMapping("/me")
    public String currentUser(Authentication authentication) {
        return authentication.getName();
    }
}
