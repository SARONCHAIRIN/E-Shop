package com.example.learning_spring_security.Service;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
public class FirebaseNotificationService {

    public String sendToDevice(
            String fid,
            String title,
            String body,
            Map<String, String> data
    ) throws FirebaseMessagingException {

        Message.Builder messageBuilder = Message.builder()
                .setFid(fid)
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

        log.info("FCM notification sent successfully: {}", response);

        return response;
    }
}