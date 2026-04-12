package com.tm.bff.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Returns index.html for all React Router-managed paths so that page refresh
 * on any client-side route works correctly (CODING_PATTERNS.md §18).
 *
 * More specific Spring MVC mappings (/api/**, /auth/**, /oauth2/**, /actuator/**)
 * are processed first, so this controller only handles unknown GET paths.
 */
@Controller
public class SpaFallbackController {

    @GetMapping(value = {
        "/",
        "/login",
        "/register",
        "/forgot-password",
        "/mfa/verify",
        "/dashboard",
        "/settings",
        "/settings/**",
        "/admin",
        "/admin/**",
        "/auth/reset-password"
    })
    public String spa() {
        return "forward:/index.html";
    }
}
