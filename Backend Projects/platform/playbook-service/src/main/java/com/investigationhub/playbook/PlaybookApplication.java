package com.investigationhub.playbook;

import com.investigationhub.common.CommonConfiguration;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(CommonConfiguration.class)
@OpenAPIDefinition(info = @Info(title = "Investigation Hub Playbook API", version = "1.0"))
public class PlaybookApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlaybookApplication.class, args);
    }
}
