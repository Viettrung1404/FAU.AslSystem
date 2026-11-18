package com.asl.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration;

@SpringBootApplication(exclude = {
    DataRedisAutoConfiguration.class,
    DataRedisRepositoriesAutoConfiguration.class,
    DataRedisReactiveAutoConfiguration.class
})
public class AslApplication {
    public static void main(String[] args) {
        SpringApplication.run(AslApplication.class, args);
    }
}
