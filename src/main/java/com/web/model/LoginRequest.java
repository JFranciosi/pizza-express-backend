package com.web.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
                @NotBlank @Email String email,
                @NotBlank @Size(max = 128) String password) {
}
