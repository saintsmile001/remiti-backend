package com.autoinvoice;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class AutoinvoiceAgentApplication {

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        dotenv.entries().forEach(entry -> System.setProperty(entry.getKey(), entry.getValue()));
        SpringApplication.run(AutoinvoiceAgentApplication.class, args);
    }

    @Bean
    CommandLineRunner checkDb(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password) {

        return args -> {
            System.out.println("==== DATABASE CONFIG DEBUG ====");
            System.out.println("DB URL = " + url);
            System.out.println("DB USER = " + username);
            System.out.println("DB PASS = " + password);
            System.out.println("================================");
        };
    }
}