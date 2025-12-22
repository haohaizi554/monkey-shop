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

    // 1. 定义图片路径常量
    public static final String PROJECT_PATH = System.getProperty("user.dir");
    public static final String UPLOAD_PATH = PROJECT_PATH + "/src/main/resources/static/images/";

    // 2. 注入访问统计拦截器 (这是为了实现数据看板的真实访问量)
    @Autowired
    private VisitInterceptor visitInterceptor;

    // 3. 配置静态资源映射 (图片显示功能)
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/images/**")
                .addResourceLocations("file:" + UPLOAD_PATH + File.separator);
    }

    // 4. 配置拦截器 (访问统计功能)
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(visitInterceptor)
                .addPathPatterns("/", "/shop.html", "/index.html");
    }
}