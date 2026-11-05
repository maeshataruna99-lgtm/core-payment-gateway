package com.example.payment.webhook;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What: A stand-in merchant server that fails on purpose.
 *
 * <p>Why: The local demo must be able to show a webhook failing and then succeeding on retry.
 *
 * <p>How to use: Register {@code http://localhost:8080/demo/webhook-receiver?failTimes=1} as an
 * endpoint. Each event then gets a 503 that many times, followed by a 200.
 *
 * <p>Safety: Exists only in the {@code demo} profile. It reads no body and changes no records.
 */
@RestController
@Profile("demo")
class DemoWebhookReceiver {

  private final Map<String, Integer> calls = new ConcurrentHashMap<>();

  @PostMapping("/demo/webhook-receiver")
  ResponseEntity<Void> receive(
      @RequestHeader("X-PayGate-Event-Id") String eventId,
      @RequestParam(defaultValue = "0") int failTimes) {
    int call = calls.merge(eventId, 1, Integer::sum);
    return ResponseEntity.status(call <= failTimes ? 503 : 200).build();
  }
}
