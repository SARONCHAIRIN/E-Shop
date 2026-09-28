package com.example.learning_spring_security.Service;

import com.example.learning_spring_security.Model.DeviceToken;
import com.example.learning_spring_security.Repository.DeviceTokenRepository;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class FirebaseNotificationService {

    private final DeviceTokenRepository deviceTokenRepository;

    /**
     * Send FCM notification directly to one device token.
     */
    public String sendToDevice(
            String token,
            String title,
            String body,
            Map<String, String> data
    ) throws FirebaseMessagingException {

        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("FCM token cannot be empty");
        }

        String cleanToken = token.trim();

        Message.Builder messageBuilder = Message.builder()
                .setToken(cleanToken)
                .setNotification(
                        Notification.builder()
                                .setTitle(title)
                                .setBody(body)
                                .build()
                );

        if (data != null && !data.isEmpty()) {
            messageBuilder.putAllData(data);
        }

        String response = FirebaseMessaging
                .getInstance()
                .send(messageBuilder.build());

        log.info(
                "FCM notification sent successfully: {}",
                response
        );

        return response;
    }

    /**
     * Send notification to all active devices
     * registered by a specific user.
     */
    public void sendToUser(
            Long userId,
            String title,
            String body,
            Map<String, String> data
    ) {

        if (userId == null) {
            log.warn("Cannot send FCM: userId is null");
            return;
        }

        List<DeviceToken> devices =
                deviceTokenRepository
                        .findAllByUserIdAndActiveTrue(userId);

        if (devices.isEmpty()) {
            log.info(
                    "No active FCM devices found for user {}",
                    userId
            );
            return;
        }

        log.info(
                "Sending FCM notification to user {} on {} device(s)",
                userId,
                devices.size()
        );

        for (DeviceToken device : devices) {

            try {

                sendToDevice(
                        device.getToken(),
                        title,
                        body,
                        data
                );

                log.info(
                        "FCM sent to user {} device {}",
                        userId,
                        device.getId()
                );

            } catch (FirebaseMessagingException e) {

                log.error(
                        "FCM failed for user {} device {}: {}",
                        userId,
                        device.getId(),
                        e.getMessage()
                );

            } catch (Exception e) {

                log.error(
                        "Unexpected FCM error for user {} device {}: {}",
                        userId,
                        device.getId(),
                        e.getMessage()
                );
            }
        }
    }



    //Send notification to all active devices 
    public void sendToAllUsers(
        String title,
        String body,
        Map<String, String> data
) {

    List<DeviceToken> devices =
            deviceTokenRepository.findAllByActiveTrue();

    if (devices.isEmpty()) {
        log.info("No active FCM devices found");
        return;
    }

    log.info(
            "Sending broadcast FCM notification to {} device(s)",
            devices.size()
    );

    for (DeviceToken device : devices) {

        try {

            sendToDevice(
                    device.getToken(),
                    title,
                    body,
                    data
            );

            log.info(
                    "Broadcast FCM sent successfully to device {}",
                    device.getId()
            );

        } catch (FirebaseMessagingException e) {

            log.error(
                    "Broadcast FCM failed for device {}: {}",
                    device.getId(),
                    e.getMessage()
            );

        } catch (Exception e) {

            log.error(
                    "Unexpected broadcast FCM error for device {}: {}",
                    device.getId(),
                    e.getMessage()
            );
        }
    }
}
    
}