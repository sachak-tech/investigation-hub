package com.investigationhub.alerts;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/alerts")
@Tag(name = "Alerts")
public class AlertController {
    private final AlertService alerts;

    public AlertController(AlertService alerts) {
        this.alerts = alerts;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Accept a canonical alert with source idempotency")
    public AlertService.AlertView accept(@RequestBody AlertService.AcceptAlert request,
            @RequestHeader(value = "X-Actor-Id", defaultValue = "synthetic-bank") String actorId) {
        return alerts.accept(request, MDC.get("correlationId"), actorId);
    }

    @GetMapping("/{alertId}")
    @Operation(summary = "Get a canonical alert and its accepted event ID")
    public AlertService.AlertView get(@PathVariable UUID alertId) {
        return alerts.get(alertId);
    }
}
