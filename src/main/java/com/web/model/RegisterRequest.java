package com.web.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
                @NotBlank @Size(min = 3, max = 30) @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "Username can only contain letters, numbers and underscores") String username,
                @NotBlank @Size(max = 254) @Email String email,
                @NotBlank @Size(min = 8, max = 128) String password) {
}
