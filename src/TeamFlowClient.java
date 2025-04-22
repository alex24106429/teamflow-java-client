import dto.*; // Import all DTOs from the package
import dto.NamedEntity; // Explicit import for the interface

import java.io.Console;
import java.io.IOException;
// Remove unused DateTimeFormatter and FormatStyle imports
// import java.time.format.DateTimeFormatter;
// import java.time.format.FormatStyle;
import java.util.*; // Import Map, HashMap, etc.
import com.fasterxml.jackson.databind.ObjectMapper; // Needed for sprint JSON creation

public class TeamFlowClient {

    private static String authToken = null;
    private static UUID currentTeamId = null;
    private static String currentContextType = null; // e.g., "sprint", "epic"
    private static UUID currentContextId = null; // ID of the selected sprint/epic etc.

    // Remove unused formatter
    // private static final DateTimeFormatter MESSAGE_TIME_FORMATTER = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT);

    // Regex to parse the initial part of create/edit command arguments: "Name" or Name, followed by optional description
    // Handles: /cmd type "Quoted Name" :: "Quoted Desc"
    //          /cmd type UnquotedName :: Description text
    //          /cmd type "Quoted Name"
    //          /cmd type UnquotedName
    // Group 1: Quoted Name | Group 2: Unquoted Name | Group 3: Separator (::) | Group 4: Rest (Description)
    private static final java.util.regex.Pattern NAME_DESC_PATTERN = java.util.regex.Pattern.compile(
        "^(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([^\"\\s:]+))?" + // Optional Name (Quoted: G1, Unquoted: G2)
        "(?:\\s*(::)\\s*(.*))?$" // Optional Separator (G3) and Description (G4)
    );

    // Regex for edit command: <index> <name_part> [:: <desc_part>]
    // Group 1: Index | Group 2: Quoted Name | Group 3: Unquoted Name | Group 4: Separator (::) | Group 5: Rest (Desc)
    private static final java.util.regex.Pattern EDIT_ARGS_PATTERN = java.util.regex.Pattern.compile(
        "^(\\d+)\\s+" + // Index (G1)
        "(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([^\"\\s:]+))" + // Name (Quoted: G2, Unquoted: G3)
        "(?:\\s*(::)\\s*(.*))?$" // Optional Separator (G4) and Description (G5)
    );


    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        Console console = System.console();

        System.out.println("Welcome to TeamFlow Client!");

        while (true) {
            if (authToken == null) {
                try {
                    if (!loginOrRegister(scanner, console)) continue;
                } catch (IOException | InterruptedException e) {
                     System.err.println("Login/Register process error: " + e.getMessage());
                     // Decide how to handle this - exit or retry?
                     System.exit(1); // Exit for now
                }
            }

            if (currentTeamId == null) {
                 try {
                    if (!selectTeam(scanner)) continue; // selectTeam handles exit
                 } catch (IOException | InterruptedException e) {
                     System.err.println("Error selecting team: " + e.getMessage());
                     authToken = null; // Force re-login on error
                     continue;
                 }
            }

            // Context selection loop
            while(currentTeamId != null) {
                if (currentContextId == null) {
                    try {
                        if (!selectContext(scanner)) {
                            // User chose /back from context type selection
                            break; // Break inner loop to re-evaluate outer loop (currentTeamId == null)
                        }
                    } catch (IOException | InterruptedException e) {
                        System.err.println("Error selecting context: " + e.getMessage());
                        // Decide how to handle - go back to team select?
                        currentTeamId = null; // Go back to team selection
                        break;
                    }
                    // Context selected, proceed to chat.
                }

                if (currentContextId != null) {
                    startChat(scanner); // startChat handles /back internally
                }
            }
            // Reset context if we break out of the inner loop
            currentContextId = null;
            currentContextType = null;
        }
    }

    private static boolean loginOrRegister(Scanner scanner, Console console) throws IOException, InterruptedException {
        while (true) {
            System.out.println("\nWelcome! Login or Register? (login/register/exit)");
            String choice = scanner.nextLine().trim().toLowerCase();

            if ("exit".equals(choice)) {
                System.out.println("Exiting application.");
                System.exit(0);
                return false; // Unreachable
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
                Arrays.fill(passwordChars, ' '); // Clear password from memory

                try {
                    // Use the new DTO
                    LoginResponseDto response = HttpClient.performAuthRequest(choice, username, password);
                    authToken = response.getToken();
                    if (authToken == null || authToken.isEmpty()) {
                         System.err.println("Authentication failed: No token received.");
                         continue; // Ask again
                    }
                    HttpClient.setAuthToken(authToken);
                    System.out.println(capitalize(choice) + " successful!");
                    return true;
                } catch (IOException | InterruptedException e) {
                    System.err.println("Authentication failed: " + e.getMessage());
                    System.out.println("Try again or choose another option.");
                    // Don't return false here, let the loop continue
                }
            } else {
                System.out.println("Invalid choice. Please enter 'login', 'register', or 'exit'.");
            }
        }
    }


    private static boolean selectTeam(Scanner scanner) throws IOException, InterruptedException {
        List<TeamDto> teams = null; // Use dto.TeamDto
        while (true) {
            System.out.println("\nAvailable Teams:");
            try {
                teams = HttpClient.fetchTeams(); // Returns List<TeamDto>
                if (teams == null || teams.isEmpty()) {
                    System.out.println("No teams available. Use '/create team <name>' or '/create team \"<name with spaces>\"' to create one.");
                } else {
                    printList(teams); // Pass List<TeamDto> which implements List<NamedEntity>
                }
            } catch (IOException | InterruptedException e) {
                System.err.println("Failed to fetch teams: " + e.getMessage());
                System.out.println("Cannot proceed without fetching teams. Please check connection or try again later.");
                // Maybe offer retry or exit? For now, throw exception up.
                throw e;
            }

            System.out.println("Select a team number or command (/create team..., /edit team..., /delete team..., /exit):");
            String input = scanner.nextLine().trim();

            if (input.startsWith("/")) {
                String[] parts = input.split("\\s+", 3); // Split into command, type, rest
                String commandAction = parts[0];
                String commandType = parts.length > 1 ? parts[1] : "";
                String commandArgs = parts.length > 2 ? parts[2] : "";

                if ("/exit".equals(commandAction)) {
                    System.out.println("Exiting application.");
                    System.exit(0);
                    return false; // Unreachable
                } else if (!"team".equals(commandType) && !commandAction.equals("/exit")) {
                     System.out.println("Invalid command type at team selection. Expected '/<action> team ...'");
                     continue;
                }

                if ("/create".equals(commandAction)) {
                    java.util.regex.Matcher nameMatcher = NAME_DESC_PATTERN.matcher(commandArgs);
                    if (nameMatcher.matches()) {
                        String name = nameMatcher.group(1) != null ? nameMatcher.group(1) : nameMatcher.group(2);
                        if (name != null && !name.isEmpty()) {
                            createTeam(name);
                        } else {
                             System.out.println("Usage: /create team <name> or /create team \"<name with spaces>\"");
                        }
                    } else {
                         System.out.println("Usage: /create team <name> or /create team \"<name with spaces>\"");
                    }
                    continue; // Re-list
                } else if ("/edit".equals(commandAction)) {
                     java.util.regex.Matcher editMatcher = EDIT_ARGS_PATTERN.matcher(commandArgs);
                     if (editMatcher.matches()) {
                         String indexStr = editMatcher.group(1);
                         String newName = editMatcher.group(2) != null ? editMatcher.group(2) : editMatcher.group(3);
                         // Note: editTeam currently only supports name change
                         if (indexStr != null && newName != null && !newName.isEmpty()) {
                             editTeam(scanner, teams, indexStr, newName);
                         } else {
                             System.out.println("Usage: /edit team <index> <new_name> or /edit team <index> \"<new name with spaces>\"");
                         }
                     } else {
                         System.out.println("Usage: /edit team <index> <new_name> or /edit team <index> \"<new name with spaces>\"");
                     }
                     continue; // Re-list
                } else if ("/delete".equals(commandAction)) {
                    String indexStr = commandArgs.trim();
                    if (indexStr.matches("\\d+")) {
                        deleteTeam(scanner, teams, indexStr);
                    } else {
                        System.out.println("Usage: /delete team <index>");
                    }
                    continue; // Re-list
                } else {
                     System.out.println("Invalid command: " + commandAction);
                }
            } else { // User entered a number
                try {
                    int teamIndex = Integer.parseInt(input) - 1;
                    if (teams != null && teamIndex >= 0 && teamIndex < teams.size()) {
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

    // Removed unescapeQuotes method

    // Updated to use NamedEntity
    private static UUID getEntityIdFromIndex(List<? extends NamedEntity> entities, String indexStr) {
        try {
            int index = Integer.parseInt(indexStr) - 1;
            if (entities != null && index >= 0 && index < entities.size()) {
                return entities.get(index).getId();
            } else {
                System.out.println("Invalid index: " + (index + 1));
                return null;
            }
        } catch (NumberFormatException e) {
            System.out.println("Invalid index format: " + indexStr);
            return null;
        }
    }


    private static void createTeam(String teamName) {
        try {
            // Manually create JSON payload as performTeamCrud expects a string
            // Jackson ObjectMapper could be used here too for consistency, but keeping it simple
            String jsonPayload = "{\"name\": \"" + teamName.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
            TeamDto createdTeam = HttpClient.performTeamCrud("POST", null, jsonPayload);
            if (createdTeam != null) {
                System.out.println("Team created: " + createdTeam.getName());
            } else {
                 System.err.println("Failed to create team (API returned null).");
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("Failed to create team: " + e.getMessage());
        }
    }

    // Updated to use dto.TeamDto
    private static void editTeam(Scanner scanner, List<TeamDto> teams, String indexStr, String newName) {
        UUID teamId = getEntityIdFromIndex(teams, indexStr);
        if (teamId == null) return;
        try {
            // Manually create JSON payload as performTeamCrud expects a string
            String jsonPayload = "{\"name\": \"" + newName.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
            TeamDto updatedTeam = HttpClient.performTeamCrud("PUT", teamId, jsonPayload);
            if (updatedTeam != null) {
                System.out.println("Team updated to: " + updatedTeam.getName());
            } else {
                 System.err.println("Failed to edit team (API returned null or no change).");
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("Failed to edit team: " + e.getMessage());
        }
    }

    // Updated to use dto.TeamDto
    private static void deleteTeam(Scanner scanner, List<TeamDto> teams, String indexStr) {
        UUID teamId = getEntityIdFromIndex(teams, indexStr);
        if (teamId == null) return;
        System.out.print("Are you sure you want to delete this team? (yes/no): ");
        String confirmation = scanner.nextLine().trim().toLowerCase();
        if (!"yes".equals(confirmation)) {
            System.out.println("Deletion cancelled.");
            return;
        }
        try {
            // performTeamCrud returns null on successful DELETE
            HttpClient.performTeamCrud("DELETE", teamId, null);
            System.out.println("Team deleted successfully.");
        } catch (IOException | InterruptedException e) {
            System.err.println("Failed to delete team: " + e.getMessage());
        }
    }



    private static boolean selectContext(Scanner scanner) throws IOException, InterruptedException {
        while (true) {
            System.out.println("\nChoose a context type (sprint/epic/userstory/task) or /back, /exit:");
            String contextTypeInput = scanner.nextLine().trim().toLowerCase();

            if ("/exit".equals(contextTypeInput)) {
                System.out.println("Exiting application.");
                System.exit(0);
                return false;
            } else if ("/back".equals(contextTypeInput)) {
                currentTeamId = null; // Signal to go back to team selection
                return false; // Return false to break context loop and go back
            } else if (Arrays.asList("sprint", "epic", "userstory", "task").contains(contextTypeInput)) {
                currentContextType = contextTypeInput;
                if (selectContextEntity(scanner, contextTypeInput)) {
                    return true; // Entity selected, proceed to chat
                }
                // If selectContextEntity returns false, user chose /back or command failed
                currentContextId = null; // Ensure context ID is reset
                currentContextType = null; // Reset type
                // Stay in this loop to re-prompt for context type
            } else {
                System.out.println("Invalid context type.");
            }
        }
    }

    // Updated to use NamedEntity and handle List<?> from HttpClient
    private static boolean selectContextEntity(Scanner scanner, String contextType) throws IOException, InterruptedException {
        List<? extends NamedEntity> entities = null; // Use NamedEntity
        while (true) {
            System.out.println("\nAvailable " + contextType + "s:");
            List<?> rawEntities = null;
            try {
                 rawEntities = HttpClient.fetchContextEntities(contextType, currentTeamId); // Returns List<?>
            } catch (IOException | InterruptedException e) {
                 System.err.println("Failed to fetch " + contextType + "s: " + e.getMessage());
                 System.out.println("Cannot proceed. Try again or select a different context type.");
                 return false; // Go back to context type selection on fetch error
            }

            // Check and cast the raw list
            if (rawEntities == null || rawEntities.isEmpty()) {
                System.out.println("No " + contextType + "s available. Use '/create " + contextType + " <name> [:: description]' or quoted versions.");
                entities = new ArrayList<>(); // Ensure entities is not null
            } else {
                // Basic type check before casting - assumes homogeneity
                if (rawEntities.get(0) instanceof NamedEntity) {
                    entities = (List<? extends NamedEntity>) rawEntities;
                    printList(entities);
                } else {
                    System.err.println("Error: Received unexpected data type for " + contextType + "s.");
                    entities = new ArrayList<>(); // Treat as empty
                }
            }

            System.out.println("Select a " + contextType + " number or command (/create..., /edit..., /delete..., /back, /exit):");
            String input = scanner.nextLine().trim();

            if (input.startsWith("/")) {
                String[] parts = input.split("\\s+", 3); // Split into command, type, rest
                String commandAction = parts[0];
                String commandType = parts.length > 1 ? parts[1] : "";
                String commandArgs = parts.length > 2 ? parts[2] : "";

                if ("/exit".equals(commandAction)) {
                    System.out.println("Exiting application.");
                    System.exit(0);
                    return false;
                } else if ("/back".equals(commandAction)) {
                    return false; // Go back to context type selection
                } else if (!commandType.equals(contextType) && !commandAction.equals("/back") && !commandAction.equals("/exit")) {
                    System.out.println("Invalid command type. Expected '/<action> " + contextType + " ...'");
                    continue;
                }

                if ("/create".equals(commandAction)) {
                    createEntity(scanner, contextType, commandArgs); // Pass the rest of the args for parsing
                    continue; // Re-list entities after creation attempt
                } else if ("/edit".equals(commandAction)) {
                    java.util.regex.Matcher editMatcher = EDIT_ARGS_PATTERN.matcher(commandArgs);
                    if (editMatcher.matches()) {
                        String indexStr = editMatcher.group(1);
                        String newName = editMatcher.group(2) != null ? editMatcher.group(2) : editMatcher.group(3);
                        // TODO: Add description editing support if needed, parsing group 5
                        if (indexStr != null && newName != null && !newName.isEmpty()) {
                            editEntity(scanner, contextType, entities, indexStr, newName);
                        } else {
                             System.out.println("Usage: /edit " + contextType + " <index> <new_name> or /edit " + contextType + " <index> \"<new name with spaces>\"");
                        }
                    } else {
                        System.out.println("Usage: /edit " + contextType + " <index> <new_name> or /edit " + contextType + " <index> \"<new name with spaces>\"");
                    }
                    continue; // Re-list entities after edit attempt
                } else if ("/delete".equals(commandAction)) {
                    String deleteIndex = commandArgs.trim();
                    if (deleteIndex.matches("\\d+")) {
                        deleteEntity(scanner, contextType, entities, deleteIndex);
                    } else {
                        System.out.println("Usage: /delete " + contextType + " <index>");
                    }
                    continue; // Re-list entities after delete attempt
                }
                else {
                    System.out.println("Invalid command: " + commandAction);
                }
            } else { // User entered a number
                try {
                    int entityIndex = Integer.parseInt(input) - 1;
                    if (entities != null && entityIndex >= 0 && entityIndex < entities.size()) {
                        currentContextId = entities.get(entityIndex).getId();
                        System.out.println(capitalize(contextType) + " selected: " + entities.get(entityIndex).getName());
                        return true; // Entity selected, proceed
                    } else {
                        System.out.println("Invalid " + contextType + " number.");
                    }
                } catch (NumberFormatException e) {
                    System.out.println("Invalid input. Please enter a number or a command.");
                }
            }
        }
    }


    // Updated createEntity to use revised parsing logic and call new HttpClient methods
    private static void createEntity(Scanner scanner, String entityType, String args) {
        String entityName = null;
        String description = null;
        String status = null; // Status primarily for userstory/task

        java.util.regex.Matcher matcher = NAME_DESC_PATTERN.matcher(args.trim());

        if (matcher.matches()) {
            entityName = matcher.group(1) != null ? matcher.group(1) : matcher.group(2); // G1=quoted, G2=unquoted
            if (matcher.group(3) != null) { // If :: separator exists (G3)
                 description = matcher.group(4); // G4 is description
            } else if (entityName == null && matcher.group(4) != null) {
                 // Handle case like `/create task :: description only` (no name provided)
                 System.out.println("Entity name is required.");
                 printCreateUsage(entityType);
                 return;
            } else if (entityName != null && matcher.group(4) == null && matcher.group(3) == null && args.trim().contains(" ")) {
                 // Handle case like `/create task Name with spaces` (no quotes, no ::) - treat as name only
                 entityName = args.trim(); // Use the whole input as name
                 description = null;
            }
             // If only name is provided (quoted or unquoted), description remains null
        } else if (!args.trim().isEmpty() && !args.trim().contains(" ") && !args.trim().contains("\"") && !args.trim().contains(":")) {
             // Handle single unquoted word as name
             entityName = args.trim();
        }
        else {
            System.out.println("Invalid format for /create " + entityType + " command.");
            printCreateUsage(entityType);
            return;
        }

        if (entityName == null || entityName.isEmpty()) {
            System.out.println("Entity name cannot be empty.");
             printCreateUsage(entityType);
            return;
        }

        // Default status
        if ("userstory".equals(entityType) && status == null) status = "To Do";
        else if ("task".equals(entityType) && status == null) status = "TODO";

        try {
            NamedEntity createdEntity = null; // Use NamedEntity interface
            switch (entityType) {
                case "sprint":
                    System.out.println("Sprint creation via command line needs start/end dates. Use interactive prompts for now.");
                    createSprintInteractive(scanner, entityName); // Call interactive method
                    return; // Exit after handling sprint specifically

                case "epic":
                    System.out.println("Creating Epic: Name='" + entityName + "', Desc='" + description + "'");
                    createdEntity = HttpClient.createEpic(currentTeamId, entityName, description); // Returns EpicDto
                    break;

                case "userstory":
                    UUID epicId = HttpClient.fetchFirstEpicId(currentTeamId); // Simplification
                    if (epicId == null) {
                        System.err.println("Cannot create User Story: No Epics found in the current team. Create an Epic first.");
                        return;
                    }
                    System.out.println("Creating User Story: Name='" + entityName + "', Desc='" + description + "', Status='" + status + "' under Epic ID: " + epicId);
                    createdEntity = HttpClient.createUserStory(epicId, entityName, description, status); // Returns UserStoryDto
                    break;

                case "task":
                    UUID userStoryId = HttpClient.fetchFirstUserStoryId(currentTeamId); // Simplification
                    if (userStoryId == null) {
                        System.err.println("Cannot create Task: No User Stories found (in the first Epic). Create a User Story first.");
                        return;
                    }
                    System.out.println("Creating Task: Name='" + entityName + "', Desc='" + description + "', Status='" + status + "' under User Story ID: " + userStoryId);
                    createdEntity = HttpClient.createTask(userStoryId, entityName, description, status); // Returns TaskDto
                    break;

                default:
                    System.out.println("Unknown entity type for creation: " + entityType);
                    return;
            }

            if (createdEntity != null) {
                System.out.println(capitalize(entityType) + " created successfully: " + createdEntity.getName());
            } // Error message should have been printed by HttpClient if null

        } catch (IOException | InterruptedException e) {
            System.err.println("Failed to create " + entityType + ": " + e.getMessage());
        } catch (Exception e) { // Catch unexpected errors during creation/casting
            System.err.println("An unexpected error occurred while creating " + entityType + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

     private static void printCreateUsage(String entityType) {
         System.out.println("Usage: /create " + entityType + " <name>");
         System.out.println("   or: /create " + entityType + " \"<name with spaces>\"");
         System.out.println("   or: /create " + entityType + " <name> :: <description>");
         System.out.println("   or: /create " + entityType + " \"<name>\" :: \"<description>\"");
         System.out.println("   or: /create " + entityType + " <name> :: \"<description>\"");
         System.out.println("   or: /create " + entityType + " \"<name>\" :: <description>");
     }

    // Updated interactive sprint creation
    private static void createSprintInteractive(Scanner scanner, String sprintName) {
         System.out.print("Enter start date (YYYY-MM-DD): ");
         String startDateStr = scanner.nextLine();
         System.out.print("Enter end date (YYYY-MM-DD): ");
         String endDateStr = scanner.nextLine();

         // Basic validation (can be improved)
         if (!startDateStr.matches("\\d{4}-\\d{2}-\\d{2}") || !endDateStr.matches("\\d{4}-\\d{2}-\\d{2}")) {
             System.err.println("Invalid date format. Please use YYYY-MM-DD.");
             return;
         }

         // Append time for backend format - adjust if backend expects different format
         // Assuming backend expects ISO-like format without timezone offset handling here
         String startDate = startDateStr + "T00:00:00"; // Example format
         String endDate = endDateStr + "T23:59:59";   // Example format

         String path = "/sprints/start"; // Endpoint for creating/starting a sprint
         // Manually create JSON payload as performEntityCrud expects a string
         // Use a Map for easier construction and potential Jackson serialization later
         Map<String, String> payloadMap = new HashMap<>();
         payloadMap.put("teamId", currentTeamId.toString());
         payloadMap.put("name", sprintName);
         payloadMap.put("startDate", startDate);
         payloadMap.put("endDate", endDate);

         String jsonPayload;
         try {
             // Use Jackson to create the JSON string correctly
             jsonPayload = new ObjectMapper().writeValueAsString(payloadMap);
         } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
              System.err.println("Error creating JSON payload for sprint: " + e.getMessage());
              return;
         }


         try {
             // performEntityCrud returns Object, needs casting
             Object result = HttpClient.performEntityCrud("POST", "sprint", null, path, jsonPayload);
             if (result instanceof SprintDto) {
                 SprintDto createdSprint = (SprintDto) result;
                 System.out.println("Sprint created: " + createdSprint.getName());
             } else if (result != null) {
                  System.err.println("Sprint creation returned unexpected type: " + result.getClass().getName());
             } else {
                  System.err.println("Failed to create sprint (API returned null).");
             }
         } catch (IOException | InterruptedException e) {
             System.err.println("Failed to create sprint: " + e.getMessage());
         }
    }


    // Updated to use NamedEntity
    private static void editEntity(Scanner scanner, String entityType, List<? extends NamedEntity> entities, String indexStr, String newName) {
        UUID entityId = getEntityIdFromIndex(entities, indexStr);
        if (entityId == null) return;

        String path = "";
        // Manually create JSON payload as performEntityCrud expects a string
        // TODO: Add support for updating description, status etc. if needed
        String jsonPayload = "{\"name\": \"" + newName.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";

        switch (entityType) {
            case "sprint": path = "/sprints/" + entityId; break;
            case "epic": path = "/epics/" + entityId; break;
            case "userstory": path = "/user-stories/" + entityId; break;
            case "task": path = "/tasks/" + entityId; break;
            default: System.out.println("Cannot edit unknown entity type: " + entityType); return;
        }

        try {
            // performEntityCrud returns Object, needs casting
            Object result = HttpClient.performEntityCrud("PUT", entityType, entityId, path, jsonPayload);
            // Check the type before casting
            if (result instanceof NamedEntity) {
                 NamedEntity updatedEntity = (NamedEntity) result;
                 System.out.println(capitalize(entityType) + " updated to: " + updatedEntity.getName());
            } else if (result != null) {
                 System.err.println("Edit operation returned unexpected type: " + result.getClass().getName());
            } else {
                 System.err.println("Failed to edit " + entityType + " (API returned null or no change).");
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("Failed to edit " + entityType + ": " + e.getMessage());
        }
    }

    // Updated to use NamedEntity
    private static void deleteEntity(Scanner scanner, String entityType, List<? extends NamedEntity> entities, String indexStr) {
        UUID entityId = getEntityIdFromIndex(entities, indexStr);
        if (entityId == null) return;

        System.out.print("Are you sure you want to delete this " + entityType + "? (yes/no): ");
        String confirmation = scanner.nextLine().trim().toLowerCase();
        if (!"yes".equals(confirmation)) {
            System.out.println("Deletion cancelled.");
            return;
        }

        String path = "";
        switch (entityType) {
            case "sprint": path = "/sprints/" + entityId; break;
            case "epic": path = "/epics/" + entityId; break;
            case "userstory": path = "/user-stories/" + entityId; break;
            case "task": path = "/tasks/" + entityId; break;
            default: System.out.println("Cannot delete unknown entity type: " + entityType); return;
        }

        try {
            // performEntityCrud returns null on successful DELETE
            HttpClient.performEntityCrud("DELETE", entityType, entityId, path, null);
            System.out.println(capitalize(entityType) + " deleted successfully.");
        } catch (IOException | InterruptedException e) {
            System.err.println("Failed to delete " + entityType + ": " + e.getMessage());
        }
    }



    // Updated to use dto.MessageDto and handle createdAt as String
    private static void startChat(Scanner scanner) {
        System.out.println("\n--- Entering " + currentContextType + " Chat (" + currentContextId + ") ---");
        // Note: fetchMessages currently returns empty list due to unclear API endpoint
        List<MessageDto> receivedMessages = HttpClient.fetchMessages(currentContextType, currentContextId);
        if (receivedMessages != null && !receivedMessages.isEmpty()) {
            System.out.println("Recent messages:");
            receivedMessages.forEach(msg -> {
                // Use createdAt directly as String, provide default if null
                String timestamp = msg.getCreatedAt() != null ? msg.getCreatedAt() : "Timestamp N/A";
                String sender = (msg.getSender() != null && msg.getSender().getUsername() != null) ? msg.getSender().getUsername() : "Unknown";
                System.out.printf("[%s] %s: %s\n", timestamp, sender, msg.getContent());
            });
        } else {
            // System.out.println("No recent messages or failed to fetch.");
        }

        WebSocketClient.connectWebSocket(currentContextType, currentContextId, authToken);

        System.out.println("\nEnter messages to send, or /back to return to context selection, /exit to quit:");
        while (true) {
            String messageInput = scanner.nextLine();
            if ("/back".equals(messageInput)) {
                WebSocketClient.closeWebSocket();
                currentContextId = null; // Signal to go back
                currentContextType = null;
                System.out.println("--- Exiting Chat ---");
                return;
            } else if ("/exit".equals(messageInput)) {
                System.out.println("Exiting application.");
                WebSocketClient.closeWebSocket();
                System.exit(0);
                return;
            } else if (messageInput.trim().isEmpty()) {
                continue; // Ignore empty input
            }
            else {
                WebSocketClient.sendMessage(messageInput);
                // Optional delay
                try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
    }


    // Updated printList to use NamedEntity
    private static void printList(List<? extends NamedEntity> items) {
        if (items == null || items.isEmpty()) return;
        for (int i = 0; i < items.size(); i++) {
            NamedEntity item = items.get(i);
            // Extract type from class name for display
            String className = item.getClass().getSimpleName();
            String itemType = className.replace("Dto", ""); // Assumes DTO naming convention
            System.out.println((i + 1) + ". " + itemType + ": " + item.getName());
        }
    }

    // Helper to capitalize strings (unchanged)
    private static String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

}
