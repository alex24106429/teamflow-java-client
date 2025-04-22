package dto;

import java.util.List;
import java.util.UUID;

// Add Jackson annotations if needed
// import com.fasterxml.jackson.annotation.JsonProperty;

// Implement the NamedEntity interface
public class EpicDto implements NamedEntity {
    private UUID id;
    private String name;
    private String description;
    private List<UserStoryDto> userStories; // Assuming UserStoryDto exists or will be created

    // Default constructor for Jackson
    public EpicDto() {}

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

    public List<UserStoryDto> getUserStories() {
        return userStories;
    }

    public void setUserStories(List<UserStoryDto> userStories) {
        this.userStories = userStories;
    }

    // Optional: toString() for debugging
    @Override
    public String toString() {
        return "EpicDto{" +
               "id=" + id +
               ", name='" + name + '\'' +
               ", description='" + description + '\'' +
               ", userStories=" + (userStories != null ? userStories.size() : 0) + " items" +
               '}';
    }
}