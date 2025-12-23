package com.example.monkey.config;

import com.example.monkey.interceptor.VisitInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static boolean isLocalDev() {
        String os = System.getProperty("os.name").toLowerCase();
        return os.contains("win") || os.contains("mac");
    }

    // 1. 定义图片路径常量
    public static final String UPLOAD_PATH = isLocalDev()
            ? System.getProperty("user.dir") + "/src/main/resources/static/images/"
            : "/data/images/";

    // 2. 注入访问统计拦截器 (这是为了实现数据看板的真实访问量)
    @Autowired
    private VisitInterceptor visitInterceptor;

    // 3. 配置静态资源映射 (图片显示功能)
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/images/**")
                .addResourceLocations(
                        "file:" + UPLOAD_PATH,       // 1. 优先找外部上传目录 (用户上传的)
                        "classpath:/static/images/"  // 2. 找不到再去 JAR 包内找 (系统默认的)
                );
    }

    // 4. 配置拦截器 (访问统计功能)
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(visitInterceptor)
                .addPathPatterns("/", "/shop.html", "/index.html");
    }
}