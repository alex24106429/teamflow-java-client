package dto;

import java.util.UUID;
// Potentially add start/end dates later if needed
// import java.time.LocalDateTime;

// Implement the NamedEntity interface
public class SprintDto implements NamedEntity {
    private UUID id;
    private String name;
    // private LocalDateTime startDate;
    // private LocalDateTime endDate;
    // private UUID teamId; // Might be needed depending on API response

    // Default constructor for Jackson
    public SprintDto() {}

    // Getters and Setters
    // These already satisfy the NamedEntity interface requirements
    @Override
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    @Override
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    // toString()
    @Override
    public String toString() {
        return "SprintDto{" +
               "id=" + id +
               ", name='" + name + '\'' +
               '}';
    }
}