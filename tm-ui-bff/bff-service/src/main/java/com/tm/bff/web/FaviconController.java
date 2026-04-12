package com.tm.bff.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.Duration;

/**
 * Serves a bundled favicon explicitly so browsers, health checks, and bots never
 * depend on the generic static-resource exception path for /favicon.ico.
 */
@Controller
public class FaviconController {

    @GetMapping(value = "/favicon.ico", produces = "image/x-icon")
    public ResponseEntity<Resource> favicon() {
        Resource favicon = new ClassPathResource("static/favicon.ico");
        if (!favicon.exists()) {
            Resource svgFallback = new ClassPathResource("static/favicon.svg");
            if (!svgFallback.exists()) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("image/svg+xml"))
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                    .body(svgFallback);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("image/x-icon"))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                .body(favicon);
    }
}


