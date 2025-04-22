package dto;

import java.util.UUID;

/**
 * Simple interface for DTOs that have an ID and a Name,
 * useful for generic handling in the client UI.
 */
public interface NamedEntity {
    UUID getId();
    String getName();
    // Optional: Add default toString or other common methods if needed
}