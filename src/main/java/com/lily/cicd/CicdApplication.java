package com.lily.cicd;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class CicdApplication {
    public static void main(String[] args) {
        SpringApplication.run(CicdApplication.class, args);
    }
}
