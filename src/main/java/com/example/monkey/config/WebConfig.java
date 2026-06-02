package com.example.monkey.config;

import com.example.monkey.interceptor.VisitInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.upload-path:}")
    private String configuredUploadPath;

    private String detectLocalPath() {
        return System.getProperty("user.dir") + "/src/main/resources/static/images/";
    }

    public String getUploadPath() {
        if (configuredUploadPath != null && !configuredUploadPath.isEmpty()) {
            return configuredUploadPath;
        }
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win") || os.contains("mac")) {
            return detectLocalPath();
        }
        return "/data/images/";
    }

    public static String UPLOAD_PATH;

    @Autowired
    public void init() {
        UPLOAD_PATH = getUploadPath();
    }

    @Autowired
    private VisitInterceptor visitInterceptor;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/images/**")
                .addResourceLocations(
                        "file:" + getUploadPath(),
                        "classpath:/static/images/"
                );
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(visitInterceptor)
                .addPathPatterns("/", "/shop.html", "/index.html");
    }
}
