package com.example.payment.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

  private static SecretCipher cipher(String key) {
    return new SecretCipher(new WebhookProperties(key, Duration.ZERO, 1, 1, List.of()));
  }

  private final SecretCipher cipher = cipher("first-key-with-at-least-32-characters");

  @Test
  void decryptsWhatItEncrypted() {
    assertEquals("whsec_value", cipher.decrypt(cipher.encrypt("whsec_value")));
  }

  @Test
  void storedValueHidesTheSecretAndDiffersEachTime() {
    String first = cipher.encrypt("whsec_value");
    assertFalse(first.contains("whsec_value"));
    assertNotEquals(first, cipher.encrypt("whsec_value"));
  }

  @Test
  void anotherKeyCannotDecrypt() {
    String stored = cipher.encrypt("whsec_value");
    SecretCipher other = cipher("other-key-with-at-least-32-characters");
    assertThrows(IllegalStateException.class, () -> other.decrypt(stored));
  }
}
