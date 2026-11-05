package com.example.payment.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WebhookUrlPolicyTest {

  private static WebhookProperties properties(List<String> allowed) {
    return new WebhookProperties("x".repeat(32), Duration.ZERO, 1, 1, allowed);
  }

  /** Resolves literal addresses, plus names used by these tests. */
  private static InetAddress[] resolve(String host) throws UnknownHostException {
    return switch (host) {
      case "public.example" -> InetAddress.getAllByName("93.184.216.34");
      case "internal.example" -> InetAddress.getAllByName("10.1.2.3");
      case "mixed.example" ->
          new InetAddress[] {
            InetAddress.getByName("93.184.216.34"), InetAddress.getByName("192.168.0.1")
          };
      case "missing.example" -> throw new UnknownHostException(host);
      default -> InetAddress.getAllByName(host);
    };
  }

  private final WebhookUrlPolicy policy =
      new WebhookUrlPolicy(properties(List.of()), WebhookUrlPolicyTest::resolve);

  @Test
  void allowsHttpsToPublicAddress() {
    assertTrue(policy.rejectionReason("https://public.example/hook").isEmpty());
  }

  @Test
  void rejectsPlainHttp() {
    assertEquals(
        "The URL must use HTTPS.", policy.rejectionReason("http://public.example/hook").get());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://127.0.0.1/hook",
        "https://10.0.0.1/hook",
        "https://172.16.0.1/hook",
        "https://192.168.1.1/hook",
        "https://169.254.169.254/latest/meta-data",
        "https://100.64.0.1/hook",
        "https://0.0.0.0/hook",
        "https://[::1]/hook",
        "https://[fe80::1]/hook",
        "https://[fd00::1]/hook",
        "https://internal.example/hook",
        "https://mixed.example/hook"
      })
  void rejectsPrivateTargets(String url) {
    assertEquals("The URL must not point to a private address.", policy.rejectionReason(url).get());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"not a url", "https:///no-host", "https://user:pass@public.example/hook", "ftp:x"})
  void rejectsMalformedUrls(String url) {
    assertTrue(policy.rejectionReason(url).isPresent());
  }

  @Test
  void rejectsUnresolvableHost() {
    assertTrue(policy.rejectionReason("https://missing.example/hook").isPresent());
  }

  @Test
  void allowlistAppliesOnlyToListedHosts() {
    WebhookUrlPolicy local =
        new WebhookUrlPolicy(properties(List.of("localhost")), WebhookUrlPolicyTest::resolve);
    assertFalse(local.rejectionReason("http://localhost:8080/hook").isPresent());
    assertTrue(local.rejectionReason("http://127.0.0.1:8080/hook").isPresent());
  }
}
