package com.investigationhub.alerts;

import com.investigationhub.common.CommonConfiguration;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@Import(CommonConfiguration.class)
@OpenAPIDefinition(info = @Info(title = "Investigation Hub Alert API", version = "1.0"))
public class AlertApplication {
    public static void main(String[] args) {
        SpringApplication.run(AlertApplication.class, args);
    }
}
