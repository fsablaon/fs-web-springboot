package org.fsweb.facade.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fsweb.facade.client.VisitorClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HelloController {

    private final VisitorClient visitorClient;

    @GetMapping("/hello")
    public String hello(@RequestParam(defaultValue = "World") String name) {
        log.debug("Hello endpoint called with name: {}", name);
        // Each greeting counts as a visit. The greeting is returned even if the visitor backend is down.
        visitorClient.recordVisit();
        return "Hello, " + name + "!";
    }

}
