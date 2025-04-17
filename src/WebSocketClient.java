import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Import the new DTOs
import dto.MessageDto;
import dto.UserDto;

public class WebSocketClient {

    private static WebSocket websocketSession = null;
    private static CountDownLatch messageLatch = null;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
    // Removed unused receivedMessages list


    public static void connectWebSocket(String currentContextType, UUID currentContextId, String authToken) {
        HttpClient client = HttpClient.newHttpClient();
        String wsPath = "";
        switch (currentContextType) {
            case "sprint": wsPath = "/chat/sprint/" + currentContextId; break;
            case "epic": wsPath = "/chat/epic/" + currentContextId; break;
            case "userstory": wsPath = "/chat/user-story/" + currentContextId; break;
            case "task": wsPath = "/chat/task/" + currentContextId; break;
            default: return;
        }

        try {
            messageLatch = new CountDownLatch(1);
            WebSocket.Listener listener = new WebSocketClientListener();
            CompletableFuture<WebSocket> wsFuture = client.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + authToken)
                    .buildAsync(URI.create("ws://localhost:51738/chat" + wsPath), listener);
            websocketSession = wsFuture.get();
            System.out.println("WebSocket connected to " + currentContextType + " chat.");

        } catch (InterruptedException | ExecutionException e) {
            System.err.println("WebSocket connection failed: " + e.getMessage());
        }
    }

    public static void sendMessage(String messageContent) {
        if (websocketSession != null) {
            websocketSession.sendText("{\"content\": \"" + messageContent + "\"}", true);
        } else {
            System.out.println("WebSocket not connected. Message not sent.");
        }
    }

    public static void closeWebSocket() {
        if (websocketSession != null) {
            websocketSession.sendClose(WebSocket.NORMAL_CLOSURE, "User initiated close");
            websocketSession = null;
        }
    }


    public static class WebSocketClientListener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            String messageJson = data.toString();
             Pattern messagePattern = Pattern.compile("\\{\"id\":\"([^\"]+)\",\"content\":\"([^\"]*)\",\"sender\":\\{\"id\":\"([^\"]+)\",\"username\":\"([^\"]+)\".*?\\},.*?\"createdAt\":\"([^\"]+)\".*?\\}");
            Matcher matcher = messagePattern.matcher(messageJson);
            if (matcher.find()) {
                // Use the imported DTOs directly
                MessageDto message = new MessageDto();
                message.setContent(matcher.group(2));
                UserDto sender = new UserDto();
                sender.setUsername(matcher.group(4));
                message.setSender(sender);
                // Handle potential parsing errors for createdAt
                try {
                    String createdAtString = matcher.group(5);
                    // Adjust parsing if milliseconds are not always present
                    if (createdAtString.contains(".")) {
                         createdAtString = createdAtString.substring(0, createdAtString.indexOf('.'));
                    }
                    message.setCreatedAt(LocalDateTime.parse(createdAtString, DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                     System.out.printf("\n%s (%s): %s\n> ", message.getSender().getUsername(), message.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), message.getContent()); // Print new message with prompt
                } catch (Exception e) {
                    System.err.println("Error parsing createdAt: " + matcher.group(5) + " - " + e.getMessage());
                    System.out.println("Received raw message: " + messageJson); // Print raw message on error
                    System.out.print("> "); // Re-print prompt
                }

            } else {
                System.out.println("Received unparsed message: " + messageJson); // Fallback for unparsed messages
                System.out.print("> "); // Re-print prompt
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }


        @Override
        public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            System.out.println("WebSocket closed: " + statusCode + " - " + reason);
            messageLatch.countDown();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            System.err.println("WebSocket error: " + error.getMessage());
            messageLatch.countDown();
        }
    }
}