package com.example.payment.common;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs a timestamp and body with HMAC-SHA256.
 *
 * <p>The timestamp is part of the signed text, so an old signature cannot be reused with a new
 * timestamp.
 */
public final class HmacSigner {

  private HmacSigner() {}

  public static String sign(String secret, long epochSeconds, String body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] signature = mac.doFinal((epochSeconds + "." + body).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(signature);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is required by the Java platform", e);
    }
  }

  /** Compares in constant time, so the check does not reveal how much of a guess was right. */
  public static boolean matches(String secret, long epochSeconds, String body, String signature) {
    return signature != null
        && MessageDigest.isEqual(
            sign(secret, epochSeconds, body).getBytes(StandardCharsets.UTF_8),
            signature.getBytes(StandardCharsets.UTF_8));
  }
}
