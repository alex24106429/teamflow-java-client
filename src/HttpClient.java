import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HttpClient {

    private static final String API_BASE_URL = "http://localhost:51738/api";
    private static String authToken;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");


    public static void setAuthToken(String token) {
        authToken = token;
    }

    public static String getAuthToken() {
        return authToken;
    }


    public static LoginResponse performAuthRequest(String action, String username, String password) throws IOException, InterruptedException {
        URL url = new URL(API_BASE_URL + "/" + action);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);

        String jsonInputString = String.format("{\"username\": \"%s\", \"password\": \"%s\"}", username, password);
        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = jsonInputString.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int responseCode = connection.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    response.append(responseLine.trim());
                }
                return parseLoginResponse(response.toString());
            }
        } else {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
                StringBuilder errorResponse = new StringBuilder();
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    errorResponse.append(responseLine.trim());
                }
                throw new IOException("Authentication failed: " + responseCode + " - " + errorResponse.toString());
            }
        }
    }

    private static LoginResponse parseLoginResponse(String jsonResponse) {
        Pattern tokenPattern = Pattern.compile("\"token\":\"([^\"]+)\"");
        Matcher tokenMatcher = tokenPattern.matcher(jsonResponse);
        String token = tokenMatcher.find() ? tokenMatcher.group(1) : null;
        return new LoginResponse(token, null); // Message not really needed for client.
    }


    public static List<TeamDto> fetchTeams() {
        try {
            String response = sendGetRequest("/teams");
            return parseTeamList(response);
        } catch (IOException | InterruptedException e) {
            System.err.println("Failed to fetch teams: " + e.getMessage());
            return null;
        }
    }

    private static List<TeamDto> parseTeamList(String jsonResponse) {
        List<TeamDto> teams = new ArrayList<>();
        Pattern teamPattern = Pattern.compile("\\{\"id\":\"([^\"]+)\",\"name\":\"([^\"]+)\".*?\\}");
        Matcher matcher = teamPattern.matcher(jsonResponse);
        int index = 1;
        while (matcher.find()) {
            teams.add(new TeamDto(UUID.fromString(matcher.group(1)), matcher.group(2), index++));
        }
        return teams;
    }


    public static TeamDto performTeamCrud(String method, UUID teamId, String jsonPayload) throws IOException, InterruptedException {
        String path = "/teams";
        if (teamId != null) {
            path += "/" + teamId;
        }
        HttpURLConnection connection = prepareCrudConnection(method, path);
        if (jsonPayload != null && !method.equals("DELETE")) {
            connection.setDoOutput(true);
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }
        }

        int responseCode = connection.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {
            if (method.equals("DELETE")) return null; // DELETE returns no body
            try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    response.append(responseLine.trim());
                }
                return parseTeamDto(response.toString());
            }
        } else if (responseCode == HttpURLConnection.HTTP_NO_CONTENT && method.equals("DELETE")) {
            return null; // Successful delete
        }
        else {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
                StringBuilder errorResponse = new StringBuilder();
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    errorResponse.append(responseLine.trim());
                }
                throw new IOException(String.format("Team operation failed (%s): %d - %s", method, responseCode, errorResponse.toString()));
            }
        }
    }

    private static TeamDto parseTeamDto(String jsonResponse) {
        Pattern teamPattern = Pattern.compile("\\{\"id\":\"([^\"]+)\",\"name\":\"([^\"]+)\".*?\\}");
        Matcher matcher = teamPattern.matcher(jsonResponse);
        if (matcher.find()) {
            return new TeamDto(UUID.fromString(matcher.group(1)), matcher.group(2), -1); // Index not relevant here
        }
        return null;
    }


    public static List<? extends ContextEntityDto> fetchContextEntities(String contextType, UUID currentTeamId) {
        String path = "";
        switch (contextType) {
            case "sprint": path = "/sprints/teams/" + currentTeamId + "/sprints"; break;
            case "epic": path = "/epics?teamId=" + currentTeamId; break;
            case "userstory": path = "/user-stories?epicId=" + fetchFirstEpicId("epic", currentTeamId); break; // Simplification: first epic for user stories
            case "task": path = "/tasks?userStoryId=" + fetchFirstUserStoryId("userstory", currentTeamId); break; // Simplification: first user story for tasks
            default: return null;
        }
        try {
            String response = sendGetRequest(path);
            return parseContextEntityList(response, contextType);
        } catch (IOException | InterruptedException e) {
            System.err.println("Failed to fetch " + contextType + "s: " + e.getMessage());
            return null;
        }
    }

     static UUID fetchFirstEpicId(String contextType, UUID currentTeamId) {
        List<EpicDto> epics = (List<EpicDto>) fetchContextEntities("epic", currentTeamId);
        return epics != null && !epics.isEmpty() ? epics.get(0).getId() : null;
    }

    static UUID fetchFirstUserStoryId(String contextType, UUID currentTeamId) {
        List<UserStoryDto> userStories = (List<UserStoryDto>) fetchContextEntities("userstory", currentTeamId);
        return userStories != null && !userStories.isEmpty() ? userStories.get(0).getId() : null;
    }


    private static List<? extends ContextEntityDto> parseContextEntityList(String jsonResponse, String contextType) {
        List<ContextEntityDto> entities = new ArrayList<>();
        Pattern entityPattern = Pattern.compile("\\{\"id\":\"([^\"]+)\",\"name\":\"([^\"]+)\".*?\\}");
        Matcher matcher = entityPattern.matcher(jsonResponse);
        int index = 1;
        while (matcher.find()) {
            UUID id = UUID.fromString(matcher.group(1));
            String name = matcher.group(2);
            switch (contextType) {
                case "sprint": entities.add(new SprintDto(id, name, index++)); break;
                case "epic": entities.add(new EpicDto(id, name, index++)); break;
                case "userstory": entities.add(new UserStoryDto(id, name, index++)); break;
                case "task": entities.add(new TaskDto(id, name, index++)); break;
            }
        }
        return entities;
    }


    public static ContextEntityDto performEntityCrud(String method, String entityType, UUID entityId, String path, String jsonPayload) throws IOException, InterruptedException {
        String fullPath = API_BASE_URL + path;
        HttpURLConnection connection = prepareCrudConnection(method, path);

        if (jsonPayload != null && !method.equals("DELETE")) {
            connection.setDoOutput(true);
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }
        }

        int responseCode = connection.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {
            if (method.equals("DELETE")) return null; // DELETE returns no body
            try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    response.append(responseLine.trim());
                }
                return parseContextEntityDto(response.toString(), entityType);
            }
        } else if (responseCode == HttpURLConnection.HTTP_NO_CONTENT && method.equals("DELETE")) {
            return null; // Successful delete
        }
        else {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
                StringBuilder errorResponse = new StringBuilder();
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    errorResponse.append(responseLine.trim());
                }
                throw new IOException(String.format("%s operation failed (%s): %d - %s", entityType, method, responseCode, errorResponse.toString()));
            }
        }
    }

    private static ContextEntityDto parseContextEntityDto(String jsonResponse, String entityType) {
         Pattern entityPattern = Pattern.compile("\\{\"id\":\"([^\"]+)\",\"name\":\"([^\"]+)\".*?\\}");
        Matcher matcher = entityPattern.matcher(jsonResponse);
        if (matcher.find()) {
            UUID id = UUID.fromString(matcher.group(1));
            String name = matcher.group(2);
            switch (entityType) {
                case "sprint": return new SprintDto(id, name, -1);
                case "epic": return new EpicDto(id, name, -1);
                case "userstory": return new UserStoryDto(id, name, -1);
                case "task": return new TaskDto(id, name, -1);
            }
        }
        return null;
    }


    private static HttpURLConnection prepareCrudConnection(String method, String path) throws IOException {
        URL url = new URL(API_BASE_URL + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + authToken);
        return connection;
    }


    public static String sendGetRequest(String path) throws IOException, InterruptedException {
        URL url = new URL(API_BASE_URL + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Authorization", "Bearer " + authToken);

        int responseCode = connection.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    response.append(responseLine.trim());
                }
                return response.toString();
            }
        } else {
            throw new IOException("GET request failed: " + responseCode);
        }
    }


    public static List<MessageDto> fetchMessages(String currentContextType, UUID currentContextId) {
        String path = "";
        switch (currentContextType) {
            case "sprint": path = "/sprints/" + currentContextId + "/messages"; break;
            case "epic": path = "/epics/" + currentContextId + "/messages"; break;
            case "userstory": path = "/user-stories/" + currentContextId + "/messages"; break;
            case "task": path = "/tasks/" + currentContextId + "/messages"; break;
            default: return null;
        }
        try {
            String response = sendGetRequest(path);
            return parseMessages(response);
        } catch (IOException | InterruptedException e) {
            System.err.println("Failed to fetch messages: " + e.getMessage());
            return null;
        }
    }

    public static List<MessageDto> parseMessages(String jsonResponse) {
        List<MessageDto> messages = new ArrayList<>();
        Pattern messagePattern = Pattern.compile("\\{\"id\":\"([^\"]+)\",\"content\":\"([^\"]*)\",\"sender\":\\{\"id\":\"([^\"]+)\",\"username\":\"([^\"]+)\".*?\\},.*?\"createdAt\":\"([^\"]+)\".*?\\}");
        Matcher matcher = messagePattern.matcher(jsonResponse);
        while (matcher.find()) {
            MessageDto message = new MessageDto();
            message.setContent(matcher.group(2));
            UserDto sender = new UserDto();
            sender.setUsername(matcher.group(4));
            message.setSender(sender);
            message.setCreatedAt(LocalDateTime.parse(matcher.group(5).substring(0, matcher.group(5).indexOf('.')), DATE_TIME_FORMATTER)); //Basic date parsing, adjust as needed
            messages.add(message);
        }
        return messages;
    }


    // DTO classes (simplified for client)
    public static class LoginResponse {
        private String token;
        private String message;

        public LoginResponse(String token, String message) {
            this.token = token;
            this.message = message;
        }
        public String getToken() { return token; }
    }

    public static class TeamDto implements ContextEntityDto {
        private UUID id;
        private String name;
        private int index;
        public TeamDto(UUID id, String name, int index) { this.id = id; this.name = name; this.index = index;}
        public UUID getId() { return id; }
        public String getName() { return name; }
        public String toString() { return index + ". Team: " + name + ", ID: " + id; }
    }

    public static class SprintDto implements ContextEntityDto {
        private UUID id;
        private String name;
        private int index;
        public SprintDto(UUID id, String name, int index) { this.id = id; this.name = name; this.index = index;}
        public UUID getId() { return id; }
        public String getName() { return name; }
        public String toString() { return index + ". Sprint: " + name + ", ID: " + id; }
    }

    public static class EpicDto implements ContextEntityDto {
        private UUID id;
        private String name;
        private int index;
        public EpicDto(UUID id, String name, int index) { this.id = id; this.name = name; this.index = index;}
        public UUID getId() { return id; }
        public String getName() { return name; }
        public String toString() { return index + ". Epic: " + name + ", ID: " + id; }
    }

    public static class UserStoryDto implements ContextEntityDto {
        private UUID id;
        private String name;
        private int index;
        public UserStoryDto(UUID id, String name, int index) { this.id = id; this.name = name; this.index = index;}
        public UUID getId() { return id; }
        public String getName() { return name; }
        public String toString() { return index + ". User Story: " + name + ", ID: " + id; }
    }

    public static class TaskDto implements ContextEntityDto {
        private UUID id;
        private String name;
        private int index;
        public TaskDto(UUID id, String name, int index) { this.id = id; this.name = name; this.index = index;}
        public UUID getId() { return id; }
        public String getName() { return name; }
        public String toString() { return index + ". Task: " + name + ", ID: " + id; }
    }

    public interface ContextEntityDto {
        UUID getId();
        String getName();
    }

    public static class MessageDto {
        private String content;
        private UserDto sender;
        private LocalDateTime createdAt;

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public UserDto getSender() { return sender; }
        public void setSender(UserDto sender) { this.sender = sender; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    }

    public static class UserDto {
        private String username;
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
    }
}