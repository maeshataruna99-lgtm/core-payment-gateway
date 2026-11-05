package com.example.payment.provider;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Selects the provider adapter for a payment method. */
@Component
public class ProviderRegistry {

  private final Map<PaymentMethod, PaymentProvider> providers;

  public ProviderRegistry(List<PaymentProvider> providers) {
    this.providers = providers.stream().collect(Collectors.toMap(PaymentProvider::method, p -> p));
  }

  public PaymentProvider forMethod(PaymentMethod method) {
    PaymentProvider provider = providers.get(method);
    if (provider == null) {
      throw new IllegalStateException("No provider for " + method);
    }
    return provider;
  }
}
