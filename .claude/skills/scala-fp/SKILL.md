---
name: scala3-fp-readability
description: Enforces Scala 3 functional programming and strong type-level design. Prioritizes readability, simplicity, and maintainability over cleverness. Rejects hardcoding, weak typing, and overly complex abstractions. Use when reviewing or writing Scala 3 code.
---

# Scala 3 Functional Programming Readability

## Principles

- Scala 3 only. Prefer modern syntax and language features.
- Prefer compile-time guarantees over runtime checks.
- Make illegal states unrepresentable.
- Explicit > implicit.
- Types should guide readability and correctness.
- Code should be simple, easy to reason about, and maintainable.

## Rules

### Functional Programming

- Prefer pure functions. Side effects must be explicit via effect types (IO, ZIO, etc.).
- Disallow mutable state unless explicitly justified.
- No raw Future usage; require effect systems with controlled execution.
- Prefer referential transparency.
- Keep functions short and composable.

### Type-Level Design

- Require domain-specific types instead of primitives (no Stringly-typed APIs).
- Prefer ADTs (sealed traits + case classes) for domain modeling.
- Use phantom types or type parameters to encode states and capabilities.
- Reject Boolean parameters; model alternatives with ADTs.
- Prefer total functions; partial functions must be justified and isolated.
- Enforce exhaustiveness in pattern matching.
- Favor clarity over clever type-level tricks that reduce readability.

### Hardcoding

- Reject hardcoded configuration values (timeouts, limits, names, routing keys).
- Reject magic strings, magic numbers, and inline literals in business logic.
- Require configuration or typed constants with clear ownership.

### Readability & Maintainability

- Code should be easy to read and reason about.
- Avoid nested or overly abstracted code that obscures intent.
- Favor naming clarity over brevity.
- Prefer straightforward solutions that leverage types rather than clever hacks.

## Review Behavior

- Call out weak typing as a design flaw.
- Flag runtime validation that could be encoded in types.
- Reject "simpler" solutions that reduce type safety.
- Reject clever solutions that reduce readability.

## Anti-patterns

- String-based identifiers where a value class or opaque type is possible.
- if/else chains encoding state machines.
- Boolean flags controlling behavior.
- Runtime casts, reflection, or unchecked pattern matches.
- Convenience over correctness.
- Overly abstracted or deeply nested code that is hard to follow.

## Tone

- Be direct and critical.
- Treat type weakness or unreadable code as a correctness bug.
- Do not soften feedback.
