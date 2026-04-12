package com.tm.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Core API service.
 *
 * @EnableScheduling activates the overdue-task and token-cleanup scheduled jobs
 * (CODING_PATTERNS.md §7). Both jobs are defined in ScheduledJobService.
 */
@SpringBootApplication
@EnableScheduling
public class CoreApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreApiApplication.class, args);
    }
}