package com.example.payment.payment;

import com.example.payment.payment.PaymentStateMachine.State;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Stores payment attempts. */
interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {

  /** Locks the row, so concurrent events on one attempt apply one at a time. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select a from PaymentAttempt a where a.id = :id")
  Optional<PaymentAttempt> findByIdForUpdate(@Param("id") UUID id);

  List<PaymentAttempt> findByCheckoutIdOrderByCreatedAtDesc(UUID checkoutId, Limit limit);

  /** Attempts whose outcome must be asked of the provider. */
  @Query(
      """
      select a.id from PaymentAttempt a
      where a.state = :unknown or (a.state = :created and a.createdAt < :createdBefore)
      """)
  List<UUID> findIdsToCheck(
      @Param("unknown") State unknown,
      @Param("created") State created,
      @Param("createdBefore") Instant createdBefore,
      Limit limit);

  List<PaymentAttempt> findByStateInAndExpiresAtLessThanEqual(
      List<State> states, Instant now, Limit limit);
}
