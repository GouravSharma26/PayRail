# Official Reference Links

Use these references when choosing versions and validating implementation details. Check current pages at Phase 0 and again before major dependency upgrades; do not rely on this documentation snapshot indefinitely.

- Spring Boot system requirements: https://docs.spring.io/spring-boot/system-requirements.html
- Spring Boot documentation: https://docs.spring.io/spring-boot/index.html
- Java 21 documentation: https://docs.oracle.com/en/java/javase/21/
- Maven Wrapper: https://maven.apache.org/wrapper/
- PostgreSQL current documentation: https://www.postgresql.org/docs/current/
- PostgreSQL support/version policy: https://www.postgresql.org/support/versioning/
- Apache Kafka documentation: https://kafka.apache.org/documentation/
- Kafka producer configuration (idempotence, acknowledgments, retries): https://kafka.apache.org/documentation/#producerconfigs
- Spring for Apache Kafka documentation: https://docs.spring.io/spring-kafka/reference/
- Flyway documentation: https://documentation.red-gate.com/flyway
- Testcontainers Java modules: https://java.testcontainers.org/
- Spring Boot Actuator: https://docs.spring.io/spring-boot/reference/actuator/index.html
- Micrometer documentation: https://micrometer.io/docs
- Antigravity rules/workspace instructions: https://www.antigravity.google/docs/rules/
- Antigravity agent overview: https://www.antigravity.google/docs/agent/

## Version policy

Choose exact compatible versions during Phase 0 and record them in `docs/MODEL_AND_TECH_DECISIONS.md`. Prefer the supported stable Spring Boot line compatible with Java 21 and the selected ecosystem. Do not automatically upgrade a framework major version mid-phase. Confirm broker image and Testcontainers compatibility and pin image tags rather than using `latest`.
