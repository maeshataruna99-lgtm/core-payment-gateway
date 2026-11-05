# ADR-002: Spring Boot 4

Status: Accepted, 2026-11-05

## Context

The project blueprint suggests Spring Boot 3.x and asks that versions be verified before implementation. On 2026-11-05, start.spring.io offered only 4.x releases, with 4.1.2 as the default.

## Decision

Use Spring Boot 4.1.2 on Java 21 LTS. Maven manages versions through the Spring Boot parent. Versions outside that parent are pinned in `apps/backend/pom.xml`.

## Consequences

- The project starts on a release line that Spring Initializr currently offers.
- Spring Boot 4 uses modular starters, such as `spring-boot-starter-webmvc` and `spring-boot-starter-flyway`, and Testcontainers 2 package names. Older guides may not apply directly.
