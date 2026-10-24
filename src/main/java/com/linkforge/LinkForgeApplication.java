package com.linkforge;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
// Explicit mapper packages: a broad "com.linkforge" scan also proxied plain
// interfaces like ClickEventPublisher into mapper beans.
@MapperScan({"com.linkforge.user.mapper", "com.linkforge.link.mapper", "com.linkforge.stats.mapper", "com.linkforge.apikey.mapper", "com.linkforge.webhook.mapper"})
public class LinkForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(LinkForgeApplication.class, args);
    }
}
