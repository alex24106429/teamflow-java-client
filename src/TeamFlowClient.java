import java.io.Console;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;
import java.time.format.DateTimeFormatter;

public class TeamFlowClient {

    private static String authToken = null;
    private static UUID currentTeamId = null;
    private static String currentContextType = null;
    private static UUID currentContextId = null;
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
                    HttpClient.LoginResponse response = HttpClient.performAuthRequest(choice, username, password);
                    authToken = response.getToken();
                    HttpClient.setAuthToken(authToken); // Set token in HttpClient as well
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


    private static boolean selectTeam(Scanner scanner) {
        while (true) {
            System.out.println("\nAvailable Teams:");
            List<HttpClient.TeamDto> teams = HttpClient.fetchTeams();
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


    private static void createTeam(Scanner scanner) {
        System.out.print("Enter team name: ");
        String teamName = scanner.nextLine();
        try {
            HttpClient.TeamDto createdTeam = HttpClient.performTeamCrud("POST", null, String.format("{\"name\": \"%s\"}", teamName));
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
            HttpClient.TeamDto updatedTeam = HttpClient.performTeamCrud("PUT", teamId, String.format("{\"name\": \"%s\"}", teamName));
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
            boolean success = HttpClient.performTeamCrud("DELETE", teamId, null) != null; // DELETE returns void, check for exception instead
            if (success) {
                System.out.println("Team deleted.");
                currentTeamId = null; // Reset current team
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("Failed to delete team: " + e.getMessage());
        }
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
            List<? extends HttpClient.ContextEntityDto> entities = HttpClient.fetchContextEntities(contextType, currentTeamId);
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


    private static void createEntity(Scanner scanner, String entityType) {
        System.out.print("Enter " + entityType + " name: ");
        String entityName = scanner.nextLine();
        String path = "";
        String jsonPayloadFormat = "{\"name\": \"%s\"}";
        UUID parentId = null;

        switch (entityType) {
            case "sprint": path = "/sprints/start"; jsonPayloadFormat = "{\"teamId\": \"%s\", \"name\": \"%s\", \"startDate\": \"%s\", \"endDate\": \"%s\"}"; parentId = currentTeamId; break; // Need start/end dates
            case "epic": path = "/epics?teamId=" + currentTeamId; break;
            case "userstory": path = "/user-stories?epicId=" + HttpClient.fetchFirstEpicId("epic", currentTeamId); break; // Simplification
            case "task": path = "/tasks?userStoryId=" + HttpClient.fetchFirstUserStoryId("userstory", currentTeamId); break; // Simplification
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
            HttpClient.ContextEntityDto createdEntity = HttpClient.performEntityCrud("POST", entityType, null, path, jsonPayload);
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
            HttpClient.ContextEntityDto updatedEntity = HttpClient.performEntityCrud("PUT", entityType, entityId, path, jsonPayload);
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
            boolean success = HttpClient.performEntityCrud("DELETE", entityType, entityId, path, null) != null; // DELETE returns void
            if (success) {
                System.out.println(entityType + " deleted.");
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("Failed to delete " + entityType + ": " + e.getMessage());
        }
    }



    private static void startChat(Scanner scanner) {
        List<HttpClient.MessageDto> receivedMessages = HttpClient.fetchMessages(currentContextType, currentContextId);
        if (receivedMessages != null) {
            receivedMessages.forEach(msg -> {
                System.out.printf("%s (%s): %s\n", msg.getSender().getUsername(), msg.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), msg.getContent());
            });
        }

        WebSocketClient.connectWebSocket(currentContextType, currentContextId, authToken);

        System.out.println("\nEnter messages to send, or /back to return to context selection, /exit to quit:");
        while (true) {
            String messageInput = scanner.nextLine();
            if ("/back".equals(messageInput)) {
                WebSocketClient.closeWebSocket();
                return;
            } else if ("/exit".equals(messageInput)) {
                System.out.println("Exiting application.");
                WebSocketClient.closeWebSocket();
                System.exit(0);
                return;
            } else {
                WebSocketClient.sendMessage(messageInput);
            }
        }
    }



    private static void printList(List<? extends HttpClient.ContextEntityDto> items, String itemName) {
        for (int i = 0; i < items.size(); i++) {
            System.out.println((i + 1) + ". " + itemName + ": " + items.get(i).getName());
        }
    }


}
