package com.example.deploymentconsole;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class DeploymentConsoleApplication {
    public static void main(String[] args) {
        SpringApplication.run(DeploymentConsoleApplication.class, args);
    }
}
