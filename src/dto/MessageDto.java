package dto;

import java.util.UUID;
// Import Jackson annotations if needed
// import com.fasterxml.jackson.annotation.JsonProperty;

public class MessageDto {
    private UUID id;
    private String content;
    private UserDto sender;
    private String createdAt; // Kept as String due to JavaTimeModule issues

    // Added fields based on API response
    private UUID sprintId;
    private UUID epicId;
    private UUID userStoryId;
    private UUID taskId;
    private String contextType; // e.g., "EPIC", "SPRINT"

    // Default constructor for Jackson
    public MessageDto() {}

    // Getters and Setters for existing fields
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public UserDto getSender() { return sender; }
    public void setSender(UserDto sender) { this.sender = sender; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    // Getters and Setters for new fields
    public UUID getSprintId() { return sprintId; }
    public void setSprintId(UUID sprintId) { this.sprintId = sprintId; }
    public UUID getEpicId() { return epicId; }
    public void setEpicId(UUID epicId) { this.epicId = epicId; }
    public UUID getUserStoryId() { return userStoryId; }
    public void setUserStoryId(UUID userStoryId) { this.userStoryId = userStoryId; }
    public UUID getTaskId() { return taskId; }
    public void setTaskId(UUID taskId) { this.taskId = taskId; }
    public String getContextType() { return contextType; }
    public void setContextType(String contextType) { this.contextType = contextType; }


    // Optional: toString() for debugging
    @Override
    public String toString() {
        return "MessageDto{" +
               "id=" + id +
               ", content='" + content + '\'' +
               ", sender=" + (sender != null ? sender.getUsername() : "null") +
               ", createdAt='" + createdAt + '\'' +
               ", sprintId=" + sprintId +
               ", epicId=" + epicId +
               ", userStoryId=" + userStoryId +
               ", taskId=" + taskId +
               ", contextType='" + contextType + '\'' +
               '}';
    }
}