package com.example.payment.webhook;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * What: Decides whether the gateway may send a webhook to a URL.
 *
 * <p>Why: A merchant supplies the URL. Without this check it could point the gateway at internal
 * services or a cloud metadata address (SSRF).
 *
 * <p>How: Requires HTTPS and rejects a URL if any address its host resolves to is not public. It
 * runs at registration and again before every send, because DNS can change.
 *
 * <p>Safety: Hosts in {@code allowedHosts} skip these rules, so only the local demo may set it.
 * Redirects are never followed.
 *
 * <p>Tests: {@code WebhookUrlPolicyTest}.
 */
// shortcut: the HTTP client resolves DNS again after this check, which leaves a short DNS rebinding
// window; pin the checked address or use an egress proxy before handling untrusted merchants.
@Component
class WebhookUrlPolicy {

  /** Finds the addresses of a host. */
  interface Resolver {
    InetAddress[] resolve(String host) throws UnknownHostException;
  }

  private static final int MAX_URL_LENGTH = 2048;

  private final Set<String> allowedHosts;
  private final Resolver resolver;

  @Autowired
  WebhookUrlPolicy(WebhookProperties properties) {
    this(properties, InetAddress::getAllByName);
  }

  WebhookUrlPolicy(WebhookProperties properties, Resolver resolver) {
    this.allowedHosts = Set.copyOf(properties.allowedHosts());
    this.resolver = resolver;
  }

  /** Returns why a URL is not allowed, or empty if it is allowed. */
  Optional<String> rejectionReason(String url) {
    URI uri;
    try {
      uri = new URI(url);
    } catch (Exception e) {
      return Optional.of("The URL is not valid.");
    }
    String host = uri.getHost();
    if (url.length() > MAX_URL_LENGTH || host == null || uri.getUserInfo() != null) {
      return Optional.of("The URL is not valid.");
    }
    if (allowedHosts.contains(host)) {
      return Optional.empty();
    }
    if (!"https".equals(uri.getScheme())) {
      return Optional.of("The URL must use HTTPS.");
    }
    try {
      for (InetAddress address : resolver.resolve(host)) {
        if (!isPublic(address)) {
          return Optional.of("The URL must not point to a private address.");
        }
      }
    } catch (UnknownHostException e) {
      return Optional.of("The URL's host could not be resolved.");
    }
    return Optional.empty();
  }

  private static boolean isPublic(InetAddress address) {
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) {
      return false;
    }
    byte[] bytes = address.getAddress();
    if (address instanceof Inet6Address) {
      // fc00::/7, unique local addresses.
      return (bytes[0] & 0xfe) != 0xfc;
    }
    int first = bytes[0] & 0xff;
    int second = bytes[1] & 0xff;
    // 100.64.0.0/10 is carrier-grade NAT, 0.0.0.0/8 is "this network", and 192.0.0.0/24 is
    // reserved.
    boolean carrierNat = first == 100 && second >= 64 && second <= 127;
    boolean reserved = first == 0 || (first == 192 && second == 0 && (bytes[2] & 0xff) == 0);
    return !carrierNat && !reserved;
  }
}
