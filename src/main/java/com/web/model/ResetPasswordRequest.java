package com.web.model;

public record ResetPasswordRequest(String token, String newPassword) {
}
