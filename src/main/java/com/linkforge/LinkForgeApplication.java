package com.linkforge;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.linkforge")
public class LinkForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(LinkForgeApplication.class, args);
    }
}
