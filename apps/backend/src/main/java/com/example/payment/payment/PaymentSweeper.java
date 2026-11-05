package com.example.payment.payment;

import com.example.payment.payment.PaymentStateMachine.Event;
import com.example.payment.payment.PaymentStateMachine.State;
import com.example.payment.provider.PaymentProvider;
import com.example.payment.provider.ProviderRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * What: Resolves attempts that no incoming event will finish.
 *
 * <p>Why: Decision 1 requires a status check for {@code UNKNOWN} attempts, a crash can leave an
 * attempt in {@code CREATED}, and an unpaid attempt must eventually expire.
 *
 * <p>How: Asks the provider about each such attempt outside any transaction, then applies the
 * result. Each attempt is handled alone, so one failure does not block the others.
 *
 * <p>How to use: Set {@code payment.sweeper.cron}. The value {@code -} turns the schedule off.
 *
 * <p>Tests: {@code PaymentApiIntegrationTest}.
 */
// shortcut: assumes one running instance; add a lock such as ShedLock if there are ever several.
@Component
class PaymentSweeper {

  private static final Logger LOG = LoggerFactory.getLogger(PaymentSweeper.class);
  private static final Limit BATCH = Limit.of(100);
  private static final Duration CREATED_GRACE = Duration.ofMinutes(1);
  private static final List<State> EXPIRABLE = List.of(State.CREATED, State.PENDING);

  private final PaymentAttemptRepository attempts;
  private final PaymentEventService events;
  private final ProviderRegistry providers;
  private final Clock clock;

  PaymentSweeper(
      PaymentAttemptRepository attempts,
      PaymentEventService events,
      ProviderRegistry providers,
      Clock clock) {
    this.attempts = attempts;
    this.events = events;
    this.providers = providers;
    this.clock = clock;
  }

  @Scheduled(cron = "${payment.sweeper.cron}")
  void run() {
    Instant now = clock.instant();
    attempts
        .findIdsToCheck(State.UNKNOWN, State.CREATED, now.minus(CREATED_GRACE), BATCH)
        .forEach(this::check);
    attempts.findByStateInAndExpiresAtLessThanEqual(EXPIRABLE, now, BATCH).forEach(this::expire);
  }

  private void check(UUID attemptId) {
    try {
      PaymentAttempt attempt = attempts.findById(attemptId).orElseThrow();
      PaymentProvider provider = providers.forMethod(attempt.method());
      PaymentProvider.Result result;
      try {
        result = provider.checkStatus(attempt.toProviderRequest());
      } catch (PaymentProvider.TimeoutException e) {
        if (attempt.state() != State.UNKNOWN) {
          events.apply(attemptId, Event.TIMEOUT, null, "sweeper");
        }
        return;
      }
      events.applyProviderResult(attemptId, result, "sweeper");
    } catch (RuntimeException e) {
      LOG.error("Could not check attempt {}", attemptId, e);
    }
  }

  private void expire(PaymentAttempt attempt) {
    try {
      events.apply(attempt.id(), Event.EXPIRY, null, "sweeper");
    } catch (RuntimeException e) {
      LOG.error("Could not expire attempt {}", attempt.id(), e);
    }
  }
}
