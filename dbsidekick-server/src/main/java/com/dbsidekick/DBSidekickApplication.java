package com.dbsidekick;

import com.dbsidekick.ai.SidekickAiProperties;
import com.dbsidekick.config.SidekickProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({SidekickProperties.class, SidekickAiProperties.class})
public class DBSidekickApplication {

    public static void main(String[] args) {
        SpringApplication.run(DBSidekickApplication.class, args);
    }
}
