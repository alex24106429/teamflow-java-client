import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
// Remove the explicit import for JavaTimeModule
// import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dto.*; // Import all DTOs

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class HttpClient {

    private static final String API_BASE_URL = "http://localhost:51738/api"; // Ensure this matches your backend port
    private static String authToken;

    // Jackson ObjectMapper instance
    // Remove the JavaTimeModule registration to avoid classpath issues
    private static final ObjectMapper objectMapper = new ObjectMapper()
            // .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()) // REMOVED
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false); // Be lenient with unknown fields

    public static void setAuthToken(String token) {
        authToken = token;
    }

    public static String getAuthToken() {
        return authToken;
    }

    // Login/Register Request DTO (simple alternative to Map)
    private static class AuthRequest {
        public String username;
        public String password;

        public AuthRequest(String username, String password) {
            this.username = username;
            this.password = password;
        }
    }

    public static LoginResponseDto performAuthRequest(String action, String username, String password) throws IOException, InterruptedException {
        URL url = new URL(API_BASE_URL + "/" + action); // Assuming action is "login" or "register"
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);

        AuthRequest authRequest = new AuthRequest(username, password);
        String jsonInputString = objectMapper.writeValueAsString(authRequest);

        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = jsonInputString.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int responseCode = connection.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                return objectMapper.readValue(br, LoginResponseDto.class);
            }
        } else {
            String errorDetails = readErrorStream(connection);
            throw new IOException("Authentication failed: " + responseCode + " - " + errorDetails);
        }
    }


    public static List<TeamDto> fetchTeams() throws IOException, InterruptedException {
        try {
            String response = sendGetRequest("/teams");
            return objectMapper.readValue(response, new TypeReference<List<TeamDto>>() {});
        } catch (IOException | InterruptedException e) {
            System.err.println("Failed to fetch teams: " + e.getMessage());
            // Re-throw or handle more gracefully depending on requirements
            throw e; // Or return Collections.emptyList();
        }
    }


    // Note: Accepts pre-formatted jsonPayload for PUT/POST to maintain compatibility with current TeamFlowClient
    // Ideally, this should accept a DTO. Parses the response using Jackson.
    public static TeamDto performTeamCrud(String method, UUID teamId, String jsonPayload) throws IOException, InterruptedException {
        String path = "/teams";
        if (teamId != null) {
            path += "/" + teamId;
        }
        HttpURLConnection connection = prepareCrudConnection(method, path);

        if (jsonPayload != null && (method.equals("POST") || method.equals("PUT"))) {
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
                return objectMapper.readValue(br, TeamDto.class);
            }
        } else if (responseCode == HttpURLConnection.HTTP_NO_CONTENT && method.equals("DELETE")) {
            return null; // Successful delete
        } else {
            String errorDetails = readErrorStream(connection);
            throw new IOException(String.format("Team operation failed (%s): %d - %s", method, responseCode, errorDetails));
        }
    }


    // Returns List<?> because the specific DTO type depends on contextType.
    // Caller needs to handle casting or use instanceof.
    public static List<?> fetchContextEntities(String contextType, UUID currentTeamId) throws IOException, InterruptedException {
        String path = "";
        TypeReference<?> typeRef = null;

        switch (contextType) {
            case "sprint":
                path = "/sprints/teams/" + currentTeamId + "/sprints";
                typeRef = new TypeReference<List<SprintDto>>() {};
                break;
            case "epic":
                path = "/epics?teamId=" + currentTeamId;
                typeRef = new TypeReference<List<EpicDto>>() {};
                break;
            case "userstory":
                UUID epicId = fetchFirstEpicId(currentTeamId); // Fetch epic ID first
                if (epicId == null) {
                    System.err.println("No epics found for team " + currentTeamId + ". Cannot fetch user stories.");
                    return new ArrayList<>(); // Return empty list if no parent epic
                }
                path = "/user-stories?epicId=" + epicId;
                typeRef = new TypeReference<List<UserStoryDto>>() {};
                break;
            case "task":
                UUID userStoryId = fetchFirstUserStoryId(currentTeamId); // Fetch user story ID first
                if (userStoryId == null) {
                     System.err.println("No user stories found for team " + currentTeamId + ". Cannot fetch tasks.");
                    return new ArrayList<>(); // Return empty list if no parent user story
                }
                path = "/tasks?userStoryId=" + userStoryId;
                typeRef = new TypeReference<List<TaskDto>>() {};
                break;
            default:
                System.err.println("Unknown context type: " + contextType);
                return new ArrayList<>(); // Or throw exception
        }

        try {
            String response = sendGetRequest(path);
            if (typeRef != null) {
                // Add explicit cast here
                return (List<?>) objectMapper.readValue(response, typeRef);
            } else {
                return new ArrayList<>(); // Should not happen if contextType is valid
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("Failed to fetch " + contextType + "s: " + e.getMessage());
            throw e; // Or return empty list
        }
    }

     // Helper method, potentially refactor to avoid multiple fetches if performance is critical
     static UUID fetchFirstEpicId(UUID currentTeamId) throws IOException, InterruptedException {
        String path = "/epics?teamId=" + currentTeamId;
        try {
            String response = sendGetRequest(path);
            List<EpicDto> epics = objectMapper.readValue(response, new TypeReference<List<EpicDto>>() {});
            return epics != null && !epics.isEmpty() ? epics.get(0).getId() : null;
        } catch (IOException | InterruptedException e) {
            System.err.println("Failed to fetch epics for team " + currentTeamId + ": " + e.getMessage());
            throw e;
        }
    }

    // Helper method, potentially refactor
    static UUID fetchFirstUserStoryId(UUID currentTeamId) throws IOException, InterruptedException {
        UUID epicId = fetchFirstEpicId(currentTeamId);
        if (epicId == null) return null;

        String path = "/user-stories?epicId=" + epicId;
        try {
            String response = sendGetRequest(path);
            List<UserStoryDto> userStories = objectMapper.readValue(response, new TypeReference<List<UserStoryDto>>() {});
            return userStories != null && !userStories.isEmpty() ? userStories.get(0).getId() : null;
        } catch (IOException | InterruptedException e) {
            System.err.println("Failed to fetch user stories for epic " + epicId + ": " + e.getMessage());
            throw e;
        }
    }


    // Note: Accepts pre-formatted jsonPayload for PUT/POST. Parses response using Jackson.
    // Returns Object, caller needs to cast based on entityType.
    public static Object performEntityCrud(String method, String entityType, UUID entityId, String path, String jsonPayload) throws IOException, InterruptedException {
        HttpURLConnection connection = prepareCrudConnection(method, path); // Path should include ID or query params as needed

        if (jsonPayload != null && (method.equals("POST") || method.equals("PUT"))) {
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
                Class<?> dtoClass = getDtoClassForEntityType(entityType);
                if (dtoClass != null) {
                    return objectMapper.readValue(br, dtoClass);
                } else {
                    throw new IOException("Unknown entity type for response parsing: " + entityType);
                }
            }
        } else if (responseCode == HttpURLConnection.HTTP_NO_CONTENT && method.equals("DELETE")) {
            return null; // Successful delete
        } else {
            String errorDetails = readErrorStream(connection);
            throw new IOException(String.format("%s operation failed (%s on %s): %d - %s",
                    capitalize(entityType), method, path, responseCode, errorDetails));
        }
    }

    // Helper to get DTO class from entity type string
    private static Class<?> getDtoClassForEntityType(String entityType) {
        switch (entityType) {
            case "sprint": return SprintDto.class;
            case "epic": return EpicDto.class;
            case "userstory": return UserStoryDto.class;
            case "task": return TaskDto.class;
            case "team": return TeamDto.class; // Added for consistency if needed elsewhere
            default: return null;
        }
    }


    // --- NEW CREATE METHODS (Using Jackson for Request Body) ---

    public static EpicDto createEpic(UUID teamId, String name, String description) throws IOException, InterruptedException {
        String path = "/epics?teamId=" + teamId;
        Map<String, String> payload = new HashMap<>();
        payload.put("name", name);
        payload.put("description", description);
        String jsonPayload = objectMapper.writeValueAsString(payload);
        // Cast the result, assuming performEntityCrud returns the correct type based on entityType
        return (EpicDto) performEntityCrud("POST", "epic", null, path, jsonPayload);
    }

    public static UserStoryDto createUserStory(UUID epicId, String name, String description, String status) throws IOException, InterruptedException {
        String path = "/user-stories?epicId=" + epicId;
        Map<String, String> payload = new HashMap<>();
        payload.put("name", name);
        payload.put("description", description);
        if (status != null && !status.isEmpty()) {
            payload.put("status", status);
        }
        String jsonPayload = objectMapper.writeValueAsString(payload);
        return (UserStoryDto) performEntityCrud("POST", "userstory", null, path, jsonPayload);
    }

    public static TaskDto createTask(UUID userStoryId, String name, String description, String status) throws IOException, InterruptedException {
        String path = "/tasks?userStoryId=" + userStoryId;
         Map<String, String> payload = new HashMap<>();
        payload.put("name", name);
        payload.put("description", description);
         if (status != null && !status.isEmpty()) {
            payload.put("status", status);
        }
        String jsonPayload = objectMapper.writeValueAsString(payload);
        return (TaskDto) performEntityCrud("POST", "task", null, path, jsonPayload);
    }

    // --- END NEW CREATE METHODS ---


    private static HttpURLConnection prepareCrudConnection(String method, String path) throws IOException {
        URL url = new URL(API_BASE_URL + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json"); // Good practice to add Accept header
        if (authToken != null) {
            connection.setRequestProperty("Authorization", "Bearer " + authToken);
        }
        return connection;
    }


    // Sends GET request and returns the raw response body as String
    public static String sendGetRequest(String path) throws IOException, InterruptedException {
        URL url = new URL(API_BASE_URL + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        if (authToken != null) {
            connection.setRequestProperty("Authorization", "Bearer " + authToken);
        }

        int responseCode = connection.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    // Append directly, Jackson handles whitespace
                    response.append(responseLine);
                }
                return response.toString();
            }
        } else {
            String errorDetails = readErrorStream(connection);
            throw new IOException(String.format("GET request failed for path %s: %d - %s", path, responseCode, errorDetails));
        }
    }

    // Helper to read error stream
    private static String readErrorStream(HttpURLConnection connection) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
            StringBuilder errorResponse = new StringBuilder();
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
                errorResponse.append(responseLine.trim());
            }
            return errorResponse.toString();
        } catch (IOException | NullPointerException e) {
            // If reading error stream fails or it's null
            return "No error details available.";
        }
    }


    // fetchMessages updated to use correct nested URL structure
    public static List<MessageDto> fetchMessages(String currentContextType, UUID currentContextId) {
        // Validate inputs
        if (currentContextType == null || currentContextType.isEmpty() || currentContextId == null) {
            System.err.println("Error fetching messages: Context type and ID are required.");
            return new ArrayList<>();
        }

        // Determine the plural form for the URL path
        String contextTypePlural;
        switch (currentContextType.toLowerCase()) {
            case "sprint":
                contextTypePlural = "sprints";
                break;
            case "epic":
                contextTypePlural = "epics";
                break;
            case "userstory":
                contextTypePlural = "user-stories"; // Assuming kebab-case based on other paths
                break;
            case "task":
                contextTypePlural = "tasks";
                break;
            default:
                System.err.println("Error fetching messages: Unknown context type '" + currentContextType + "' for URL construction.");
                return new ArrayList<>();
        }

        // Construct the correct nested path
        String path = String.format("/%s/%s/messages",
                                    contextTypePlural,
                                    currentContextId.toString());

        try {
            String response = sendGetRequest(path);
            // Parse the JSON response into a list of MessageDto objects
            // Note: MessageDto.createdAt is String due to JavaTimeModule issues
            return objectMapper.readValue(response, new TypeReference<List<MessageDto>>() {});
        } catch (IOException | InterruptedException e) {
            System.err.println("Failed to fetch messages for " + currentContextType + "/" + currentContextId + " from path " + path + ": " + e.getMessage());
            // Optionally log the stack trace for debugging: e.printStackTrace();
            return new ArrayList<>(); // Return empty list on error
        } catch (Exception e) {
            // Catch unexpected parsing errors
             System.err.println("Unexpected error parsing messages for " + currentContextType + "/" + currentContextId + ": " + e.getMessage());
             e.printStackTrace();
             return new ArrayList<>();
        }
    }


    // Helper for capitalizing entity types in error messages (still used)
    private static String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    // Removed all inner DTO classes and ContextEntityDto interface
    // Removed escapeJsonString method
    // Removed parse* methods
}
