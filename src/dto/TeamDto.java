package dto;

import java.util.List;
import java.util.UUID;

// Add Jackson annotations for potential mapping issues if needed later
// import com.fasterxml.jackson.annotation.JsonProperty;

// Implement the NamedEntity interface
public class TeamDto implements NamedEntity {
    private UUID id;
    private String name;
    private UUID currentSprintId; // Assuming UUID, adjust if different
    private List<EpicDto> epics;
    private List<UserDto> members;

    // Default constructor for Jackson
    public TeamDto() {}

    // Getters and Setters (essential for Jackson)
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

    public UUID getCurrentSprintId() {
        return currentSprintId;
    }

    public void setCurrentSprintId(UUID currentSprintId) {
        this.currentSprintId = currentSprintId;
    }

    public List<EpicDto> getEpics() {
        return epics;
    }

    public void setEpics(List<EpicDto> epics) {
        this.epics = epics;
    }

    public List<UserDto> getMembers() {
        return members;
    }

    public void setMembers(List<UserDto> members) {
        this.members = members;
    }

    // Optional: toString() for debugging
    @Override
    public String toString() {
        return "TeamDto{" +
               "id=" + id +
               ", name='" + name + '\'' +
               ", currentSprintId=" + currentSprintId +
               ", epics=" + (epics != null ? epics.size() : 0) + " items" +
               ", members=" + (members != null ? members.size() : 0) + " items" +
               '}';
    }
}