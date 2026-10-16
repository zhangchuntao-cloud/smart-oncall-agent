package com.smartoncall.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置
 * 配置跨域和静态资源
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * 允许跨域的来源白名单。
     *
     * <p>原实现是 {@code allowedOrigins("*")} + 放行全部方法，等于任何网站都能带着
     * 用户浏览器里的凭证调这个后端。前端本来就由本应用自己托管（{@code classpath:/static/}），
     * 同源请求根本不需要 CORS —— 所以默认只放行本机来源，需要时用配置显式加。
     */
    @Value("${app.cors.allowed-origins:http://localhost:9900,http://127.0.0.1:9900}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 配置静态资源映射
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/");
    }
}
