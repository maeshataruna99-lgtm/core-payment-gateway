package com.example.payment.webhook;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * What: Encrypts webhook secrets with AES-256-GCM before they are stored.
 *
 * <p>Why: Signing needs the original secret, so it cannot be hashed. A database leak alone must not
 * reveal it.
 *
 * <p>Tests: {@code SecretCipherTest}.
 */
@Component
class SecretCipher {

  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  SecretCipher(WebhookProperties properties) {
    try {
      byte[] derived =
          MessageDigest.getInstance("SHA-256")
              .digest(properties.encryptionKey().getBytes(StandardCharsets.UTF_8));
      this.key = new SecretKeySpec(derived, "AES");
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("SHA-256 is required by the Java platform", e);
    }
  }

  String encrypt(String plaintext) {
    try {
      byte[] iv = new byte[IV_BYTES];
      random.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      byte[] stored =
          ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
      return Base64.getEncoder().encodeToString(stored);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Could not encrypt a webhook secret", e);
    }
  }

  String decrypt(String stored) {
    try {
      byte[] bytes = Base64.getDecoder().decode(stored);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES));
      byte[] plain = cipher.doFinal(bytes, IV_BYTES, bytes.length - IV_BYTES);
      return new String(plain, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Could not decrypt a webhook secret", e);
    }
  }
}
