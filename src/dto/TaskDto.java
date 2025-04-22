package dto;

import java.util.UUID;

// Implement the NamedEntity interface
public class TaskDto implements NamedEntity {
    private UUID id;
    private String name;
    private String description;
    private String status; // Assuming String, adjust if Enum (e.g., "TODO", "IN_PROGRESS", "DONE")

    // Default constructor for Jackson
    public TaskDto() {}

    // Getters and Setters
    // These already satisfy the NamedEntity interface requirements
    @Override
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    @Override
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    // toString()
    @Override
    public String toString() {
        return "TaskDto{" +
               "id=" + id +
               ", name='" + name + '\'' +
               ", description='" + description + '\'' +
               ", status='" + status + '\'' +
               '}';
    }
}