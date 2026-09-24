package com.example.learning_spring_security.Service;

import com.example.learning_spring_security.Model.DeviceToken;
import com.example.learning_spring_security.Model.User;
import com.example.learning_spring_security.Repository.DeviceTokenRepository;
import com.example.learning_spring_security.Repository.UserRepository;
import com.example.learning_spring_security.dto.Request.DeviceTokenRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeviceTokenService {

    private final DeviceTokenRepository deviceTokenRepository;
    private final UserRepository userRepository;

    @Transactional
    public DeviceToken register(
            DeviceTokenRequest request,
            Authentication authentication
    ) {

        String credential = authentication.getName();

        User user = userRepository
                .findFirstByUsernameOrEmail(credential, credential)
                .orElseThrow(() ->
                        new RuntimeException("Authenticated user not found")
                );

        DeviceToken deviceToken = deviceTokenRepository
                .findByToken(request.getToken())
                .orElseGet(DeviceToken::new);

        deviceToken.setToken(request.getToken());
        deviceToken.setPlatform(normalizePlatform(request.getPlatform()));
        deviceToken.setUser(user);
        deviceToken.setActive(true);

        return deviceTokenRepository.save(deviceToken);
    }

    private String normalizePlatform(String platform) {
        if (platform == null || platform.isBlank()) {
            return "unknown";
        }

        return platform.trim().toLowerCase();
    }
}