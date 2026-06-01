---
name: quality-mapping
description: Map project features, refactorings, and architectural decisions to the ABNT NBR ISO/IEC 25010 quality model within the Códex mini-app. Use when updating documentation, reviewing code quality, or planning new features to ensure they align with the project's quality posture.
---

# Quality Mapping (ISO/IEC 25010)

The **Códex** (`DocsActivity.kt`) is the source of truth for the project's quality. Every major architectural decision or feature should be mapped to one of the 8 quality characteristics of the ISO 25010 standard.

## 1. Quality Characteristics

- **Functional Suitability:** Does it do what it's supposed to? (e.g., Sort correctness tests).
- **Performance Efficiency:** Throughput, resource utilization, and time behavior (e.g., SIMD benchmarks, LTO).
- **Compatibility:** Co-existence and Interoperability (e.g., NDK/JNI bridge).
- **Usability:** Ease of use and learnability (e.g., the didactic "Extreme Tutorial", Apple-grade UX).
- **Reliability:** Maturity, fault tolerance (e.g., native crash handler in `jni.cpp`).
- **Security:** Data protection (e.g., `secrets.properties` for API keys).
- **Maintainability:** Modularity, reusability, analyzability (e.g., Registry pattern for benchmarks).
- **Portability:** Adaptability, installability (e.g., the template design).

## 2. Updating Códex

When adding a feature, update `DocsActivity.kt` to include the new quality mapping.

```kotlin
// Example: Mapping Gemini Integration to "Usability" and "Performance Efficiency"
QualityEntry(
    characteristic = "Usability",
    subCharacteristic = "Learnability",
    description = "AI-powered explanations in VizActivity help users understand complex algorithms.",
    status = Status.DONE
)
```

## 3. Review Process

- **Check Invariants:** Ensure the change doesn't break the "Load-bearing invariants" listed in `CLAUDE.md`.
- **Verify Design:** Does the new UI meet the "Apple-grade" polish?
- **Validate Performance:** If it's a core algorithm, run `device-harness.sh full-stats`.
