package dto;

import java.util.List;
import java.util.UUID;

public class UserDto {
    private UUID id;
    private String username;
    private boolean enabled;
    private List<String> roles;

    // Default constructor for Jackson
    public UserDto() {}

    // Getters and Setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getRoles() {
        return roles;
    }

    public void setRoles(List<String> roles) {
        this.roles = roles;
    }

    // Optional: toString() for debugging
    @Override
    public String toString() {
        return "UserDto{" +
               "id=" + id +
               ", username='" + username + '\'' +
               ", enabled=" + enabled +
               ", roles=" + roles +
               '}';
    }
}