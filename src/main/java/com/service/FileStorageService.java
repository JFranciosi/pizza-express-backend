package com.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

@ApplicationScoped
public class FileStorageService {

    private static final Logger LOG = Logger.getLogger(FileStorageService.class);
    private static final String UPLOAD_DIR = "uploads/avatars/";

    public FileStorageService() {
        try {
            Files.createDirectories(Paths.get(UPLOAD_DIR));
        } catch (IOException e) {
            LOG.error("Could not create upload directory: " + UPLOAD_DIR, e);
        }
    }

    public String saveAvatar(String userId, byte[] data, String mimeType) throws IOException {
        String extension = getExtension(mimeType);
        String filename = userId + extension;
        Path path = Paths.get(UPLOAD_DIR + filename);

        Files.write(path, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        return "avatars/" + filename;
    }

    public String saveAvatar(String userId, Path sourcePath, String mimeType) throws IOException {
        String extension = getExtension(mimeType);
        String filename = userId + extension;
        Path destinationPath = Paths.get(UPLOAD_DIR + filename);

        Files.copy(sourcePath, destinationPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        return "avatars/" + filename;
    }

    public byte[] loadAvatar(String pathStr) throws IOException {
        if (pathStr.contains("..")) {
            throw new IOException("Invalid path");
        }

        String filename = pathStr.startsWith("avatars/") ? pathStr.replace("avatars/", "") : pathStr;
        Path path = Paths.get(UPLOAD_DIR + filename);

        if (!Files.exists(path)) {
            return null;
        }

        return Files.readAllBytes(path);
    }

    private String getExtension(String mimeType) {
        return switch (mimeType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/x-icon" -> ".ico";
            case "image/gif" -> ".gif";
            default -> ".jpg";
        };
    }
}
