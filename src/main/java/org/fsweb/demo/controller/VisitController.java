package org.fsweb.demo.controller;

import lombok.RequiredArgsConstructor;
import org.fsweb.demo.dto.VisitCount;
import org.fsweb.demo.service.VisitCounterService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/visits")
@RequiredArgsConstructor
public class VisitController {

    private final VisitCounterService visitCounterService;

    @GetMapping
    public VisitCount current() {
        return new VisitCount(visitCounterService.current());
    }

    @PostMapping
    public VisitCount increment() {
        return new VisitCount(visitCounterService.increment());
    }
}
