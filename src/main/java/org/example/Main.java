package org.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 启动类。
 *
 * <p>{@code @ConfigurationPropertiesScan} 用于注册 {@code org.example.agent.guard.AgentGuardProperties}
 * —— Agent 资源护栏的配置绑定，不扫描的话 {@code agent.guard.*} 不会被绑定。
 */
@SpringBootApplication
@ConfigurationPropertiesScan("org.example")
public class Main {
    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }
}
