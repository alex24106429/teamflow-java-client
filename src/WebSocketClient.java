import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dto.MessageDto;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.WebSocketHttpHeaders;
// import org.springframework.web.socket.client.WebSocketClient; // Avoid direct import due to name clash
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;
import org.springframework.web.socket.sockjs.client.RestTemplateXhrTransport; // Import fallback transport

import java.lang.reflect.Type;
import java.net.URI;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class WebSocketClient {

    private static StompSession stompSession = null;
    private static WebSocketStompClient stompClient = null;
    private static ThreadPoolTaskScheduler taskScheduler = null;
    private static CountDownLatch connectionLatch = null;
    private static final ObjectMapper objectMapper = new ObjectMapper(); // For sending JSON

    // Keep track of context for sending messages
    private static String currentDestination = null;
    private static String currentSubscriptionTopic = null;


    public static void connectWebSocket(String currentContextType, UUID currentContextId, String authToken) {
        if (stompSession != null && stompSession.isConnected()) {
            System.out.println("Already connected.");
            return;
        }

        // Determine destination and subscription topic based on context
        switch (currentContextType.toLowerCase()) {
            case "sprint":
                currentDestination = "/app/chat/sprint/" + currentContextId;
                currentSubscriptionTopic = "/topic/chat/sprint/" + currentContextId;
                break;
            case "epic":
                currentDestination = "/app/chat/epic/" + currentContextId;
                currentSubscriptionTopic = "/topic/chat/epic/" + currentContextId;
                break;
            case "userstory": // Assuming server uses user-story
                currentDestination = "/app/chat/user-story/" + currentContextId;
                currentSubscriptionTopic = "/topic/chat/user-story/" + currentContextId;
                break;
            case "task":
                currentDestination = "/app/chat/task/" + currentContextId;
                currentSubscriptionTopic = "/topic/chat/task/" + currentContextId;
                break;
            default:
                System.err.println("Unknown context type: " + currentContextType);
                return;
        }

        // SockJS Transports
        List<Transport> transports = new ArrayList<>(2); // Increase capacity
        // Use StandardWebSocketClient for the WebSocket transport part of SockJS
        // Pass null for HttpHeaders as SockJsClient handles Origin etc. based on URI
        transports.add(new WebSocketTransport(new StandardWebSocketClient()));
        transports.add(new RestTemplateXhrTransport()); // Add XHR streaming/polling fallback
        // Could add fallback transports like RestTemplateTransport if needed

        org.springframework.web.socket.client.WebSocketClient transport = new SockJsClient(transports); // Use fully qualified name
        stompClient = new WebSocketStompClient(transport);

        // Use Jackson for message conversion (ensure DTOs are compatible)
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        // Task scheduler for heartbeats, etc.
        taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.afterPropertiesSet();
        stompClient.setTaskScheduler(taskScheduler);
        // Disable default heartbeats for simplicity, server config might override
        stompClient.setDefaultHeartbeat(new long[]{0, 0});

        // Connection URL (base endpoint for SockJS)
        // Use http/https for SockJS when fallback transports are involved
        String url = "ws://localhost:51738/chat"; // Try ws:// again, let SockJS handle fallback if needed

        // Headers for the initial HTTP connection (SockJS info request) and WebSocket handshake
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.setOrigin("http://localhost:5173"); // Explicitly set Origin for handshake check

        // STOMP Headers for connection (including Auth token)
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + authToken);

        // Session Handler
        StompSessionHandler sessionHandler = new MyStompSessionHandler();

        connectionLatch = new CountDownLatch(1);
        System.out.println("Attempting to connect to WebSocket via SockJS: " + url);

        try {
            // Connect asynchronously
            stompClient.connectAsync(url, handshakeHeaders /* Pass handshake headers */, connectHeaders, sessionHandler);

            // Wait for connection for a reasonable time
            if (connectionLatch.await(15, TimeUnit.SECONDS)) { // Increased timeout slightly for fallback
                System.out.println("WebSocket connection established (via SockJS/STOMP).");
            } else {
                System.err.println("WebSocket connection timed out or failed after fallback attempts.");
                closeWebSocket(); // Clean up if connection failed
            }
        } catch (Exception e) {
            System.err.println("WebSocket connection failed: " + e.getMessage());
            e.printStackTrace();
            closeWebSocket(); // Clean up on exception
        }
    }

    public static void sendMessage(String messageContent) {
        if (stompSession != null && stompSession.isConnected() && currentDestination != null) {
            try {
                // Create a simple map or a dedicated DTO for the message payload
                // Server expects {"content": "..."}
                String payload = objectMapper.writeValueAsString(java.util.Map.of("content", messageContent));
                stompSession.send(currentDestination, payload.getBytes()); // Send as byte array
                 System.out.println("Message sent to " + currentDestination);
            } catch (JsonProcessingException e) {
                 System.err.println("Failed to serialize message: " + e.getMessage());
            } catch (IllegalStateException e) {
                System.err.println("Cannot send message: " + e.getMessage()); // e.g., session not connected
            }
        } else {
            System.out.println("WebSocket not connected or destination not set. Message not sent.");
        }
    }

    public static void closeWebSocket() {
        System.out.println("Closing WebSocket connection...");
        if (stompSession != null && stompSession.isConnected()) {
            stompSession.disconnect();
        }
        stompSession = null; // Clear session reference

        if (stompClient != null) {
             if (stompClient.isRunning()) { // Check if running before stopping
                 stompClient.stop();
             }
        }
        stompClient = null; // Clear client reference

        if (taskScheduler != null && taskScheduler.getScheduledExecutor() != null && !taskScheduler.getScheduledExecutor().isShutdown()) {
            taskScheduler.shutdown();
        }
        taskScheduler = null; // Clear scheduler reference

        currentDestination = null;
        currentSubscriptionTopic = null;
        System.out.println("WebSocket resources released.");
         // Ensure latch is counted down if connection failed or closed prematurely
        if (connectionLatch != null && connectionLatch.getCount() > 0) {
            connectionLatch.countDown();
        }
    }

    // Inner class for STOMP Session Handling
    private static class MyStompSessionHandler extends StompSessionHandlerAdapter {

        @Override
        public Type getPayloadType(StompHeaders headers) {
            // Define the type Jackson should deserialize incoming messages into
            return MessageDto.class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            // This method is called for received messages
            if (payload instanceof MessageDto) {
                MessageDto message = (MessageDto) payload;
                String senderUsername = (message.getSender() != null) ? message.getSender().getUsername() : "Unknown";
                String createdAtStr = (message.getCreatedAt() != null) ? message.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : "No timestamp";
                System.out.printf("\n%s (%s): %s\n> ", senderUsername, createdAtStr, message.getContent());
            } else {
                 System.out.println("Received unexpected payload type: " + (payload != null ? payload.getClass().getName() : "null"));
                 System.out.print("> "); // Re-print prompt
            }
        }

        @Override
        public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
            System.out.println("STOMP session connected."); // Removed problematic getUri() call
            stompSession = session; // Store the session

            // Subscribe to the topic determined during connection setup
            if (currentSubscriptionTopic != null) {
                System.out.println("Subscribing to topic: " + currentSubscriptionTopic);
                session.subscribe(currentSubscriptionTopic, this); // 'this' handles received messages via handleFrame
            } else {
                 System.err.println("Cannot subscribe: Subscription topic not set.");
            }

            connectionLatch.countDown(); // Signal that connection is complete
        }

        @Override
        public void handleException(StompSession session, StompCommand command, StompHeaders headers, byte[] payload, Throwable exception) {
            System.err.println("STOMP Exception: command=" + command + ", headers=" + headers + ", payload=" + (payload != null ? new String(payload) : "null") + ", exception=" + exception);
            exception.printStackTrace();
             // Consider closing connection or attempting reconnect based on error
             connectionLatch.countDown(); // Ensure latch is released on error
             closeWebSocket();
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            System.err.println("STOMP Transport Error: " + exception.getMessage());
            // Log the full stack trace for transport errors as they often hide the root cause
            exception.printStackTrace();
            if (exception instanceof ConnectionLostException) {
                // Handle connection loss, maybe schedule reconnect
                 System.err.println("Connection lost.");
            }
             connectionLatch.countDown(); // Ensure latch is released on error
             closeWebSocket();
        }
    }
}