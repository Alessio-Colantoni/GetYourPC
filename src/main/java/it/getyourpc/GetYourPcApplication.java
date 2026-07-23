package it.getyourpc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class GetYourPcApplication {
    public static void main(String[] args) {
        SpringApplication.run(GetYourPcApplication.class, args);
    }
}
