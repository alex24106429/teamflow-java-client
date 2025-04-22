package dto;

// Add Jackson annotations if needed
// import com.fasterxml.jackson.annotation.JsonProperty;

public class LoginResponseDto {
    private String token;
    // Assuming the API might return a message field as well, based on HttpClient.LoginResponse
    private String message;

    // Default constructor for Jackson
    public LoginResponseDto() {}

    // Getters and Setters
    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    // Optional: toString()
    @Override
    public String toString() {
        // Avoid logging the actual token for security
        return "LoginResponseDto{" +
               "token='" + (token != null ? "[present]" : "null") + '\'' +
               ", message='" + message + '\'' +
               '}';
    }
}