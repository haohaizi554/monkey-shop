package com.example.monkey.config;

import com.example.monkey.interceptor.VisitInterceptor;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final VisitInterceptor visitInterceptor;
    private final Path uploadRoot;

    public WebConfig(VisitInterceptor visitInterceptor, @Value("${app.upload.path:uploads/images}") String uploadPath) {
        this.visitInterceptor = visitInterceptor;
        this.uploadRoot = Path.of(uploadPath).toAbsolutePath().normalize();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/images/**")
                .addResourceLocations(
                        uploadRoot.toUri().toString(),
                        "classpath:/static/images/");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(visitInterceptor)
                .addPathPatterns("/", "/shop.html", "/index.html");
    }
}
