package com.example.learning_spring_security.controller;

import com.example.learning_spring_security.Model.DeviceToken;
import com.example.learning_spring_security.Service.DeviceTokenService;
import com.example.learning_spring_security.dto.Request.DeviceTokenRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/device-tokens")
@RequiredArgsConstructor
public class DeviceTokenController {

    private final DeviceTokenService deviceTokenService;

    @PostMapping
    public ResponseEntity<?> registerDevice(
            @Valid @RequestBody DeviceTokenRequest request,
            Authentication authentication
    ) {

        DeviceToken deviceToken =
                deviceTokenService.register(request, authentication);

        return ResponseEntity.ok(
                Map.of(
                        "success", true,
                        "message", "Device token registered successfully",
                        "data", Map.of(
                                "id", deviceToken.getId(),
                                "platform", deviceToken.getPlatform()
                        )
                )
        );
    }
}