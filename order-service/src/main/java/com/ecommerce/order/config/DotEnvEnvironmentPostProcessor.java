package com.ecommerce.order.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DotEnvEnvironmentPostProcessor implements EnvironmentPostProcessor {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Path path : candidatePaths()) {
            if (Files.isRegularFile(path)) {
                values.putAll(readEnvFile(path));
            }
        }
        if (!values.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource("mystoreDotEnv", values));
        }
    }

    private List<Path> candidatePaths() {
        Path userDir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        return List.of(
                userDir.resolve(".env"),
                userDir.resolve("order-service").resolve(".env"),
                userDir.resolve("MyStore_ecommerce-app").resolve(".env"),
                userDir.resolve("MyStore_ecommerce-app").resolve("order-service").resolve(".env"),
                userDir.getParent() == null ? userDir.resolve(".env") : userDir.getParent().resolve(".env")
        );
    }

    private Map<String, Object> readEnvFile(Path path) {
        Map<String, Object> values = new LinkedHashMap<>();
        try {
            for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                    continue;
                }
                int split = line.indexOf('=');
                String key = line.substring(0, split).trim();
                String value = line.substring(split + 1).trim();
                if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(key, value);
            }
        } catch (IOException ignored) {
            // optional local config
        }
        return values;
    }
}
