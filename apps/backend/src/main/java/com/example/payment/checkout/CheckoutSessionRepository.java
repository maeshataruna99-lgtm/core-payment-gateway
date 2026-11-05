package com.example.payment.checkout;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Stores checkout sessions. */
interface CheckoutSessionRepository extends JpaRepository<CheckoutSession, UUID> {}
