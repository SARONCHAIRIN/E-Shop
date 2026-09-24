package com.example.learning_spring_security.controller;

import com.example.learning_spring_security.Service.FirebaseNotificationService;
import com.example.learning_spring_security.dto.Request.TestNotificationRequest;
import com.google.firebase.messaging.FirebaseMessagingException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final FirebaseNotificationService firebaseNotificationService;

    @PostMapping("/test")
    public ResponseEntity<?> sendTestNotification(
            @Valid @RequestBody TestNotificationRequest request
    ) throws FirebaseMessagingException {

        String messageId = firebaseNotificationService.sendToDevice(
                request.getToken(), // ✅ changed
                request.getTitle(),
                request.getBody(),
                Map.of(
                        "type", "TEST",
                        "screen", "home"
                )
        );

        return ResponseEntity.ok(
                Map.of(
                        "success", true,
                        "message", "Notification sent successfully",
                        "messageId", messageId
                )
        );
    }
}