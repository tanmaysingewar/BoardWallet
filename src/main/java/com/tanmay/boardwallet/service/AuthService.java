package com.tanmay.boardwallet.service;

import com.tanmay.boardwallet.dto.registration.RegistrationRequestDTO;
import com.tanmay.boardwallet.dto.registration.RegistrationResponseDTO;
import com.tanmay.boardwallet.entity.User;
import com.tanmay.boardwallet.exception.DuplicateResourceException;
import com.tanmay.boardwallet.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, BCryptPasswordEncoder passwordEncoder){
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public RegistrationResponseDTO registration(RegistrationRequestDTO registrationRequestDTO){
        boolean userExist = userRepository.findByUsername(registrationRequestDTO.getUsername()).isPresent();

        if(userExist){
            throw new DuplicateResourceException("Username already exists");
        }

        User user = new User();
        user.setUsername(registrationRequestDTO.getUsername());
        user.setPassword(passwordEncoder.encode(registrationRequestDTO.getPassword()));
        user.setEnabled(true);

        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            boolean usernameExists = userRepository
                    .findByUsername(registrationRequestDTO.getUsername())
                    .isPresent();

            if (usernameExists) {
                throw new DuplicateResourceException("Username already exists");
            }

            throw exception;
        }
        RegistrationResponseDTO registrationResponseDTO = new RegistrationResponseDTO();
        registrationResponseDTO.setId(user.getId());
        registrationResponseDTO.setUsername(user.getUsername());
        registrationResponseDTO.setEnabled(user.getEnabled());
        registrationResponseDTO.setMessage("Successfully registered");

        return registrationResponseDTO;
    }
}
