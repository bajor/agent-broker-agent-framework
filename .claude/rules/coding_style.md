# Coding Style Rules

## Language
- All code must be written in Scala 3
- Prefer to use {} rather than tabs
- Do not overcomplicate code, keep it simple and readable

## Dependencies
- Use as few dependencies as possible
- Prefer standard library and built-in solutions
- Only add external dependencies when absolutely necessary

## Type System Usage
- Write in type-level style
- Leverage Scala's type system to enforce correctness at compile time
- **Make invalid states unrepresentable**
  - Design types so that illegal states cannot be constructed
  - Use opaque types, sealed traits/enums, and other advanced type features when appropriate
  - Push validation and constraints into the type system rather than runtime checks

## Build Requirements
- **CRITICAL**: Run `make` after every code change
- Verify that `make` completes successfully with no errors
- Do not consider any change complete until `make` builds cleanly
- If `make` fails, fix the issue immediately before proceeding

## Workflow
1. Make code changes
2. Run `make`
3. Fix any compilation errors
4. Repeat until clean build
5. Only then is the change complete
