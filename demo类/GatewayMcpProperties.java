package com.example.aigateway.mcp;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(GatewayMcpProperties.PREFIX)
public record GatewayMcpProperties(
        boolean enabled,
        @NotNull URI baseUrl,
        @NotBlank String protocolVersion,
        @NotEmpty List<@Valid Application> applications,
        String clientToken,
        @NotNull Duration connectTimeout,
        @NotNull Duration responseTimeout) {

    /** Spring Boot configuration prefix exposed by the SDK. */
    public static final String PREFIX = "nebula.ai-gateway";

    public GatewayMcpProperties {
        protocolVersion = protocolVersion == null ? "2025-11-25" : protocolVersion;
        applications = applications == null ? List.of() : List.copyOf(applications);
        clientToken = clientToken == null ? "" : clientToken;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        responseTimeout = responseTimeout == null ? Duration.ofSeconds(60) : responseTimeout;
    }

    public void validateUniqueApplications() {
        if (baseUrl == null || baseUrl.toString().isBlank()) {
            throw new IllegalArgumentException(PREFIX + ".base-url must not be blank");
        }
        Set<String> duplicateNames = duplicates(applications.stream().map(Application::name).toList());
        if (!duplicateNames.isEmpty()) {
            throw new IllegalArgumentException("Duplicate gateway MCP application names=" + duplicateNames);
        }
    }

    private static Set<String> duplicates(List<String> values) {
        Set<String> seen = new java.util.HashSet<>();
        return values.stream().filter(value -> !seen.add(value)).collect(Collectors.toUnmodifiableSet());
    }

    public record Application(@NotBlank String name) {
    }
}
