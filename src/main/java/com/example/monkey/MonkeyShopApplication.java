package com.example.monkey;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class MonkeyShopApplication {

    public static void main(String[] args) {
        SpringApplication.run(MonkeyShopApplication.class, args);
    }
}