package dto;

import java.util.UUID;

// Add Jackson annotations if needed
// import com.fasterxml.jackson.annotation.JsonProperty;

// Implement the NamedEntity interface
public class UserStoryDto implements NamedEntity {
    private UUID id;
    private String name;
    private String description;
    private String status; // Assuming String, adjust if it's an Enum or other type

    // Default constructor for Jackson
    public UserStoryDto() {}

    // Getters and Setters
    // These already satisfy the NamedEntity interface requirements
    @Override
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    @Override
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    // Optional: toString() for debugging
    @Override
    public String toString() {
        return "UserStoryDto{" +
               "id=" + id +
               ", name='" + name + '\'' +
               ", description='" + description + '\'' +
               ", status='" + status + '\'' +
               '}';
    }
}