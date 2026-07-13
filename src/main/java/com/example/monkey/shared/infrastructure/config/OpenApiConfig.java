package com.example.monkey.shared.infrastructure.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

@Configuration
@OpenAPIDefinition(
        info =
                @Info(
                        title = "MonkeyShop API",
                        version = "v1",
                        description = "Canonical OpenAPI 3 contract for the MonkeyShop production API."),
        servers = @Server(url = "/", description = "Current host"))
public class OpenApiConfig {

    static {
        SpringDocUtils.getConfig().addAnnotationsToIgnore(AuthenticationPrincipal.class);
    }
}
