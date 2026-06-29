package com.example.monkey.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info =
                @Info(
                        title = "MonkeyShop API",
                        version = "v1",
                        description = "Canonical OpenAPI 3 contract for the MonkeyShop production API."),
        servers = @Server(url = "/api/v1", description = "Version 1 API"))
public class OpenApiConfig {}
