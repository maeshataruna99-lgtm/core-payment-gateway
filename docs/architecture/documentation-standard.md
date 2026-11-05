# Documentation Standard

The full template applies to services, APIs, scripts, and migrations, as set by decision 10 in [decisions-payment-invariants.md](decisions-payment-invariants.md). Other code needs a comment only where it explains a decision the code cannot show.

Explain decisions, not syntax. Leave out any section with nothing useful to say, except in scripts. If behavior changes, update its documentation and tests in the same change.

## Java template

```java
/**
 * What: What this class does.
 *
 * <p>Why: The problem it solves.
 *
 * <p>How: Important decisions, state transitions, dependencies, and side effects.
 *
 * <p>How to use: How to call, configure, or extend it.
 *
 * <p>Safety: What must never happen, and which invariants or permissions apply.
 *
 * <p>Tests: The tests that prove its behavior.
 */
```

## Migration template

```sql
-- What: What this migration changes.
-- Why: Why the change is needed.
-- How to use: Anything a developer must know before depending on it.
-- Data: Whether it changes or deletes data.
-- Testing: The test that covers it.
```

## Script template

Every script starts with all of these, as in [smoke-test.sh](../../scripts/smoke-test.sh):

```sh
# Purpose:
# Why:
# Prerequisites:
# How to run:
# Inputs:
# Expected output:
# Failure behavior:
# Data: Whether it changes or deletes data.
```
