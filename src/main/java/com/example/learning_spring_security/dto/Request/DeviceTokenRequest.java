package com.example.learning_spring_security.dto.Request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DeviceTokenRequest {

    @NotBlank(message = "FCM token is required")
    private String token;

    private String platform;
}