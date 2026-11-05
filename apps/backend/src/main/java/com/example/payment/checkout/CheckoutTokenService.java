package com.example.payment.checkout;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/**
 * What: Issues and checks the token that lets a customer read one checkout.
 *
 * <p>Why: A customer has no API key. A token derived from the checkout ID needs no storage, and an
 * idempotent replay can return the same checkout URL.
 *
 * <p>How: The token is an HMAC-SHA256 of the checkout ID.
 *
 * <p>Safety: Comparison takes constant time. Changing the secret invalidates all tokens.
 *
 * <p>Tests: {@code CheckoutTokenServiceTest}.
 */
@Service
public class CheckoutTokenService {

  private final SecretKeySpec key;

  public CheckoutTokenService(CheckoutProperties properties) {
    this.key =
        new SecretKeySpec(properties.tokenSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
  }

  public String issue(UUID checkoutId) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
      byte[] signature = mac.doFinal(checkoutId.toString().getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is required by the Java platform", e);
    }
  }

  public boolean isValid(UUID checkoutId, String token) {
    return token != null
        && MessageDigest.isEqual(
            issue(checkoutId).getBytes(StandardCharsets.UTF_8),
            token.getBytes(StandardCharsets.UTF_8));
  }
}
