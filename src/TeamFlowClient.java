import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocket.Listener;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class TeamFlowClient {

    private static final String API_BASE_URL = "http://localhost:51738/api";
    private static String authToken = null;
    private static UUID currentTeamId = null;
    private static String currentContextType = null;
    private static UUID currentContextId = null;
    private static WebSocket websocketSession = null;
    private static CountDownLatch messageLatch = null;
    private static List<MessageDto> receivedMessages = new ArrayList<>();
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");


    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        Console console = System.console();

        System.out.println("Welcome to TeamFlow Client!");

        while (true) {
            if (authToken == null) {
                if (!loginOrRegister(scanner, console)) continue;
            }

            if (currentTeamId == null) {
                if (!selectTeam(scanner)) continue;
            }

            if (currentContextId == null) {
                if (!selectContext(scanner)) continue;
            }

            startChat(scanner);

            currentContextId = null;
            currentContextType = null;
        }
    }

    private static boolean loginOrRegister(Scanner scanner, Console console) {
        while (true) {
            System.out.println("\nLogin or Register? (login/register/exit)");
            String choice = scanner.nextLine().trim().toLowerCase();

            if ("exit".equals(choice)) {
                System.out.println("Exiting application.");
                System.exit(0);
                return false; // to satisfy return type, though unreachable
            } else if ("login".equals(choice) || "register".equals(choice)) {
                System.out.print("Username: ");
                String username = scanner.nextLine();
                char[] passwordChars;
                if (console != null) {
                    passwordChars = console.readPassword("Password: ");
                } else {
                    System.out.print("Password: ");
                    passwordChars = scanner.nextLine().toCharArray();
                }
                String password = new String(passwordChars);

                try {
                    LoginResponse response = performAuthRequest(choice, username, password);
                    authToken = response.getToken();
                    System.out.println(choice.substring(0, 1).toUpperCase() + choice.substring(1) + " successful!");
                    return true;
                } catch (IOException | InterruptedException e) {
                    System.err.println("Authentication failed: " + e.getMessage());
                    System.out.println("Try again or choose another option.");
                }
            } else {
                System.out.println("Invalid choice. Please enter 'login', 'register', or 'exit'.");
            }
        }
    }

    private static LoginResponse performAuthRequest(String action, String username, String password) throws IOException, InterruptedException {
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


    private static boolean selectTeam(Scanner scanner) {
        while (true) {
            System.out.println("\nAvailable Teams:");
            List<TeamDto> teams = fetchTeams();
            if (teams == null || teams.isEmpty()) {
                System.out.println("No teams available. Use '/create team' to create one.");
            } else {
                printList(teams, "team");
            }
            System.out.println("Select a team number or command (/create team, /exit, /back):");
            String input = scanner.nextLine().trim();

            if (input.startsWith("/")) {
                if ("/exit".equals(input)) {
                    System.out.println("Exiting application.");
                    System.exit(0);
                    return false;
                } else if ("/back".equals(input)) {
                    System.out.println("Already at the team selection step.");
                } else if (input.startsWith("/create team")) {
                    createTeam(scanner);
                    return false; // Re-list teams after creation
                } else if (input.startsWith("/edit team")) {
                    editTeam(scanner, parseUuidFromCommand(input, "/edit team"));
                    return false;
                } else if (input.startsWith("/delete team")) {
                    deleteTeam(scanner, parseUuidFromCommand(input, "/delete team"));
                    return false;
                }
                else {
                    System.out.println("Invalid command at team selection.");
                }
            } else {
                try {
                    int teamIndex = Integer.parseInt(input) - 1;
                    if (teamIndex >= 0 && teamIndex < teams.size()) {
                        currentTeamId = teams.get(teamIndex).getId();
                        System.out.println("Team selected: " + teams.get(teamIndex).getName());
                        return true;
                    } else {
                        System.out.println("Invalid team number.");
                    }
                } catch (NumberFormatException e) {
                    System.out.println("Invalid input. Please enter a number or a command.");
                }
            }
        }
    }

    private static UUID parseUuidFromCommand(String command, String baseCommand) {
        String uuidString = command.substring(baseCommand.length()).trim();
        try {
            return UUID.fromString(uuidString);
        } catch (IllegalArgumentException e) {
            System.out.println("Invalid UUID format in command: " + uuidString);
            return null;
        }
    }

    private static List<TeamDto> fetchTeams() {
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

    private static void createTeam(Scanner scanner) {
        System.out.print("Enter team name: ");
        String teamName = scanner.nextLine();
        try {
            TeamDto createdTeam = performTeamCrud("POST", null, String.format("{\"name\": \"%s\"}", teamName));
            if (createdTeam != null) {
                System.out.println("Team created: " + createdTeam.getName());
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("Failed to create team: " + e.getMessage());
        }
    }

    private static void editTeam(Scanner scanner, UUID teamId) {
        if (teamId == null) return;
        System.out.print("Enter new team name: ");
        String teamName = scanner.nextLine();
        try {
            TeamDto updatedTeam = performTeamCrud("PUT", teamId, String.format("{\"name\": \"%s\"}", teamName));
            if (updatedTeam != null) {
                System.out.println("Team updated to: " + updatedTeam.getName());
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("Failed to edit team: " + e.getMessage());
        }
    }

    private static void deleteTeam(Scanner scanner, UUID teamId) {
        if (teamId == null) return;
        try {
            boolean success = performTeamCrud("DELETE", teamId, null) != null; // DELETE returns void, check for exception instead
            if (success) {
                System.out.println("Team deleted.");
                currentTeamId = null; // Reset current team
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("Failed to delete team: " + e.getMessage());
        }
    }


    private static TeamDto performTeamCrud(String method, UUID teamId, String jsonPayload) throws IOException, InterruptedException {
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


    private static boolean selectContext(Scanner scanner) {
        while (true) {
            System.out.println("\nChoose a context type (sprint/epic/userstory/task) or /back, /exit:");
            String contextTypeInput = scanner.nextLine().trim().toLowerCase();

            if ("/exit".equals(contextTypeInput)) {
                System.out.println("Exiting application.");
                System.exit(0);
                return false;
            } else if ("/back".equals(contextTypeInput)) {
                currentTeamId = null;
                return true;
            } else if (Arrays.asList("sprint", "epic", "userstory", "task").contains(contextTypeInput)) {
                currentContextType = contextTypeInput;
                if (selectContextEntity(scanner, contextTypeInput)) {
                    return true;
                }
            } else {
                System.out.println("Invalid context type.");
            }
        }
    }

    private static boolean selectContextEntity(Scanner scanner, String contextType) {
        while (true) {
            System.out.println("\nAvailable " + contextType + "s:");
            List<? extends ContextEntityDto> entities = fetchContextEntities(contextType);
            if (entities == null || entities.isEmpty()) {
                System.out.println("No " + contextType + "s available. Use '/create " + contextType + "' to create one.");
            } else {
                printList(entities, contextType);
            }
            System.out.println("Select a " + contextType + " number or command (/create " + contextType + ", /back, /exit):");
            String input = scanner.nextLine().trim();

            if (input.startsWith("/")) {
                if ("/exit".equals(input)) {
                    System.out.println("Exiting application.");
                    System.exit(0);
                    return false;
                } else if ("/back".equals(input)) {
                    currentContextId = null;
                    return true; // Go back to context type selection
                } else if (input.startsWith("/create " + contextType)) {
                    createEntity(scanner, contextType);
                    return false; // Re-list entities after creation
                } else if (input.startsWith("/edit " + contextType)) {
                    editEntity(scanner, contextType, parseUuidFromCommand(input, "/edit " + contextType));
                    return false;
                } else if (input.startsWith("/delete " + contextType)) {
                    deleteEntity(scanner, contextType, parseUuidFromCommand(input, "/delete " + contextType));
                    return false;
                }
                else {
                    System.out.println("Invalid command for " + contextType + " selection.");
                }
            } else {
                try {
                    int entityIndex = Integer.parseInt(input) - 1;
                    if (entityIndex >= 0 && entityIndex < entities.size()) {
                        currentContextId = entities.get(entityIndex).getId();
                        System.out.println(contextType + " selected: " + entities.get(entityIndex).getName());
                        return true;
                    } else {
                        System.out.println("Invalid " + contextType + " number.");
                    }
                } catch (NumberFormatException e) {
                    System.out.println("Invalid input. Please enter a number or a command.");
                }
            }
        }
    }

    private static List<? extends ContextEntityDto> fetchContextEntities(String contextType) {
        String path = "";
        switch (contextType) {
            case "sprint": path = "/sprints/teams/" + currentTeamId + "/sprints"; break;
            case "epic": path = "/epics?teamId=" + currentTeamId; break;
            case "userstory": path = "/user-stories?epicId=" + fetchFirstEpicId(); break; // Simplification: first epic for user stories
            case "task": path = "/tasks?userStoryId=" + fetchFirstUserStoryId(); break; // Simplification: first user story for tasks
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

    private static UUID fetchFirstEpicId() {
        List<EpicDto> epics = (List<EpicDto>) fetchContextEntities("epic");
        return epics != null && !epics.isEmpty() ? epics.get(0).getId() : null;
    }

    private static UUID fetchFirstUserStoryId() {
        List<UserStoryDto> userStories = (List<UserStoryDto>) fetchContextEntities("userstory");
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

    private static void createEntity(Scanner scanner, String entityType) {
        System.out.print("Enter " + entityType + " name: ");
        String entityName = scanner.nextLine();
        String path = "";
        String jsonPayloadFormat = "{\"name\": \"%s\"}";
        UUID parentId = null;

        switch (entityType) {
            case "sprint": path = "/sprints/start"; jsonPayloadFormat = "{\"teamId\": \"%s\", \"name\": \"%s\", \"startDate\": \"%s\", \"endDate\": \"%s\"}"; parentId = currentTeamId; break; // Need start/end dates
            case "epic": path = "/epics?teamId=" + currentTeamId; break;
            case "userstory": path = "/user-stories?epicId=" + fetchFirstEpicId(); break; // Simplification
            case "task": path = "/tasks?userStoryId=" + fetchFirstUserStoryId(); break; // Simplification
            default: return;
        }

        String jsonPayload;
        if ("sprint".equals(entityType)) {
            System.out.print("Enter start date (YYYY-MM-DDTHH:mm:ss): "); // ISO format, adjust as needed
            String startDate = scanner.nextLine();
            System.out.print("Enter end date (YYYY-MM-DDTHH:mm:ss): ");
            String endDate = scanner.nextLine();
            jsonPayload = String.format(jsonPayloadFormat, currentTeamId, entityName, startDate, endDate);
        }
         else {
            jsonPayload = String.format(jsonPayloadFormat, entityName);
        }


        try {
            ContextEntityDto createdEntity = performEntityCrud("POST", entityType, null, path, jsonPayload);
            if (createdEntity != null) {
                System.out.println(entityType + " created: " + createdEntity.getName());
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("Failed to create " + entityType + ": " + e.getMessage());
        }
    }

    private static void editEntity(Scanner scanner, String entityType, UUID entityId) {
        if (entityId == null) return;
        System.out.print("Enter new " + entityType + " name: ");
        String entityName = scanner.nextLine();
        String path = "";
        String jsonPayloadFormat = "{\"name\": \"%s\"}";

        switch (entityType) {
            case "sprint": path = "/sprints/" + entityId; break;
            case "epic": path = "/epics/" + entityId; break;
            case "userstory": path = "/user-stories/" + entityId; break;
            case "task": path = "/tasks/" + entityId; break;
            default: return;
        }
        String jsonPayload = String.format(jsonPayloadFormat, entityName);

        try {
            ContextEntityDto updatedEntity = performEntityCrud("PUT", entityType, entityId, path, jsonPayload);
            if (updatedEntity != null) {
                System.out.println(entityType + " updated to: " + updatedEntity.getName());
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("Failed to edit " + entityType + ": " + e.getMessage());
        }
    }

    private static void deleteEntity(Scanner scanner, String entityType, UUID entityId) {
        if (entityId == null) return;
        String path = "";
        switch (entityType) {
            case "sprint": path = "/sprints/" + entityId; break;
            case "epic": path = "/epics/" + entityId; break;
            case "userstory": path = "/user-stories/" + entityId; break;
            case "task": path = "/tasks/" + entityId; break;
            default: return;
        }

        try {
            boolean success = performEntityCrud("DELETE", entityType, entityId, path, null) != null; // DELETE returns void
            if (success) {
                System.out.println(entityType + " deleted.");
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("Failed to delete " + entityType + ": " + e.getMessage());
        }
    }


    private static ContextEntityDto performEntityCrud(String method, String entityType, UUID entityId, String path, String jsonPayload) throws IOException, InterruptedException {
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


    private static void startChat(Scanner scanner) {
        receivedMessages.clear();
        fetchMessages();
        connectWebSocket();

        System.out.println("\nEnter messages to send, or /back to return to context selection, /exit to quit:");
        while (true) {
            String messageInput = scanner.nextLine();
            if ("/back".equals(messageInput)) {
                closeWebSocket();
                return;
            } else if ("/exit".equals(messageInput)) {
                System.out.println("Exiting application.");
                closeWebSocket();
                System.exit(0);
                return;
            } else {
                sendMessage(messageInput);
            }
        }
    }

    private static void fetchMessages() {
        String path = "";
        switch (currentContextType) {
            case "sprint": path = "/sprints/" + currentContextId + "/messages"; break;
            case "epic": path = "/epics/" + currentContextId + "/messages"; break;
            case "userstory": path = "/user-stories/" + currentContextId + "/messages"; break;
            case "task": path = "/tasks/" + currentContextId + "/messages"; break;
            default: return;
        }
        try {
            String response = sendGetRequest(path);
            parseAndDisplayMessages(response);
        } catch (IOException | InterruptedException e) {
            System.err.println("Failed to fetch messages: " + e.getMessage());
        }
    }

    private static void parseAndDisplayMessages(String jsonResponse) {
        Pattern messagePattern = Pattern.compile("\\{\"id\":\"([^\"]+)\",\"content\":\"([^\"]*)\",\"sender\":\\{\"id\":\"([^\"]+)\",\"username\":\"([^\"]+)\".*?\\},.*?\"createdAt\":\"([^\"]+)\".*?\\}");
        Matcher matcher = messagePattern.matcher(jsonResponse);
        while (matcher.find()) {
            MessageDto message = new MessageDto();
            message.setContent(matcher.group(2));
            UserDto sender = new UserDto();
            sender.setUsername(matcher.group(4));
            message.setSender(sender);
            message.setCreatedAt(LocalDateTime.parse(matcher.group(5).substring(0, matcher.group(5).indexOf('.')), DATE_TIME_FORMATTER)); //Basic date parsing, adjust as needed
            receivedMessages.add(message);
        }
        receivedMessages.forEach(msg -> {
            System.out.printf("%s (%s): %s\n", msg.getSender().getUsername(), msg.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), msg.getContent());
        });
    }


    private static void connectWebSocket() {
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

    private static void sendMessage(String messageContent) {
        if (websocketSession != null) {
            websocketSession.sendText("{\"content\": \"" + messageContent + "\"}", true);
        } else {
            System.out.println("WebSocket not connected. Message not sent.");
        }
    }

    private static void closeWebSocket() {
        if (websocketSession != null) {
            websocketSession.sendClose(WebSocket.NORMAL_CLOSURE, "User initiated close");
            websocketSession = null;
        }
    }


    private static String sendGetRequest(String path) throws IOException, InterruptedException {
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


    private static void printList(List<? extends ContextEntityDto> items, String itemName) {
        for (int i = 0; i < items.size(); i++) {
            System.out.println((i + 1) + ". " + itemName + ": " + items.get(i).getName());
        }
    }


    static class WebSocketClientListener implements Listener {
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
                MessageDto message = new MessageDto();
                message.setContent(matcher.group(2));
                UserDto sender = new UserDto();
                sender.setUsername(matcher.group(4));
                message.setSender(sender);
                message.setCreatedAt(LocalDateTime.parse(matcher.group(5).substring(0, matcher.group(5).indexOf('.')), DATE_TIME_FORMATTER));

                System.out.printf("\n%s (%s): %s\n> ", message.getSender().getUsername(), message.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), message.getContent()); // Print new message with prompt
            } else {
                System.out.println("Received message: " + messageJson); // Fallback for unparsed messages
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


    // DTO classes (simplified for client)
    static class LoginResponse {
        private String token;
        private String message;

        public LoginResponse(String token, String message) {
            this.token = token;
            this.message = message;
        }
        public String getToken() { return token; }
    }

    static class TeamDto implements ContextEntityDto {
        private UUID id;
        private String name;
        private int index;
        public TeamDto(UUID id, String name, int index) { this.id = id; this.name = name; this.index = index;}
        public UUID getId() { return id; }
        public String getName() { return name; }
        public String toString() { return index + ". Team: " + name + ", ID: " + id; }
    }

    static class SprintDto implements ContextEntityDto {
        private UUID id;
        private String name;
        private int index;
        public SprintDto(UUID id, String name, int index) { this.id = id; this.name = name; this.index = index;}
        public UUID getId() { return id; }
        public String getName() { return name; }
        public String toString() { return index + ". Sprint: " + name + ", ID: " + id; }
    }

    static class EpicDto implements ContextEntityDto {
        private UUID id;
        private String name;
        private int index;
        public EpicDto(UUID id, String name, int index) { this.id = id; this.name = name; this.index = index;}
        public UUID getId() { return id; }
        public String getName() { return name; }
        public String toString() { return index + ". Epic: " + name + ", ID: " + id; }
    }

    static class UserStoryDto implements ContextEntityDto {
        private UUID id;
        private String name;
        private int index;
        public UserStoryDto(UUID id, String name, int index) { this.id = id; this.name = name; this.index = index;}
        public UUID getId() { return id; }
        public String getName() { return name; }
        public String toString() { return index + ". User Story: " + name + ", ID: " + id; }
    }

    static class TaskDto implements ContextEntityDto {
        private UUID id;
        private String name;
        private int index;
        public TaskDto(UUID id, String name, int index) { this.id = id; this.name = name; this.index = index;}
        public UUID getId() { return id; }
        public String getName() { return name; }
        public String toString() { return index + ". Task: " + name + ", ID: " + id; }
    }

    interface ContextEntityDto {
        UUID getId();
        String getName();
    }

    static class MessageDto {
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

    static class UserDto {
        private String username;
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
    }
}