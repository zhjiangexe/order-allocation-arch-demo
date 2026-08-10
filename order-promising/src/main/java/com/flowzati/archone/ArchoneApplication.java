package com.flowzati.archone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ArchoneApplication {

  public static void main(String[] args) {
    SpringApplication.run(ArchoneApplication.class, args);
  }

}
