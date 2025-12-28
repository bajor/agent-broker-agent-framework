# Code Quality Report: Agent Pipeline Framework

**Generated**: 2025-12-28
**Scope**: Full codebase review focusing on FP principles, type safety, and simplicity

---

## Executive Summary

The codebase demonstrates **excellent type-level design** with advanced Scala 3 features. The DSL is well-architected with phantom types ensuring compile-time correctness. However, there are opportunities to improve consistency, eliminate hardcoding, and enhance FP purity.

**Overall Score: 8/10**

| Category | Score | Notes |
|----------|-------|-------|
| Type Safety | 9/10 | Excellent use of opaque types and ADTs |
| FP Purity | 7/10 | Some imperative patterns remain |
| No Hardcoding | 6/10 | Several hardcoded strings and values |
| Simplicity | 8/10 | Clean DSL, some complexity in runtime |
| Documentation | 8/10 | Good, minor inaccuracies fixed |

---

## Strengths (What's Done Well)

### 1. Phantom Types for Compile-Time Safety
`dsl/AgentBuilder.scala:48-55`

```scala
final class AgentBuilder[I <: HasInput, O <: HasOutput, In, Out] private (...)
```

The phantom type pattern ensures agents cannot be built without configuring both input and output:
- `readFrom` transitions `HasInput` from `No` to `Yes`
- `writeTo` transitions `HasOutput` from `No` to `Yes`
- `.build` requires evidence `I =:= Yes` and `O =:= Yes`

**Result**: Invalid agent configurations are unrepresentable.

### 2. Opaque Types for Domain Modeling
`common/Config.scala:9-11`, `dsl/Types.scala:24-53`

```scala
opaque type Port = Int
opaque type SourceQueue = String
opaque type MaxReflections = Int
```

Benefits:
- Validated constructors (`Port.apply` returns `Option[Port]`)
- Prevents mixing incompatible values (can't use `Port` where `TimeoutSeconds` expected)
- Zero runtime overhead

### 3. Three-Way Result ADT
`dsl/Types.scala:136-139`

```scala
enum PipelineResult[+A]:
  case Success(value: A, ctx: PipelineContext)
  case Failure(error: String, ctx: PipelineContext)
  case Rejected(guardrailName: String, reason: String, ctx: PipelineContext)
```

This is superior to binary success/failure because guardrail rejections are semantically different from errors. Downstream agents can handle each case appropriately.

### 4. Kleisli Composition
`dsl/Process.scala`, `dsl/PipelineStep.scala`

The `>>>` operator enables readable pipeline composition:
```scala
val pipeline = InitState >>> GenerateCode >>> ExecuteCode >>> Summarize
```

This is railway-oriented programming done right.

---

## Issues & Recommendations

### Category 1: Hardcoding (HIGH Priority)

#### 1.1 Queue Name Pattern Duplicated
**Locations**: `dsl/Types.scala:33-34`, `dsl/Types.scala:49-50`, `dsl/AgentRuntime.scala:322-324`

The pattern `agent_${name}_tasks` appears in multiple places:

```scala
// dsl/Types.scala
def fromAgentName(agentName: String): SourceQueue = s"agent_${agentName}_tasks"
def fromAgentName(agentName: String): DestQueue = s"agent_${agentName}_tasks"

// dsl/AgentRuntime.scala
val toAgent = outputQueue.stripPrefix("agent_").stripSuffix("_tasks")
```

**Problem**: If the pattern changes, all locations must be updated.

**Recommendation**: Centralize in a single location:

```scala
object QueueNaming:
  val prefix = "agent_"
  val suffix = "_tasks"

  def toQueueName(agentName: String): String = s"$prefix${agentName}$suffix"
  def fromQueueName(queueName: String): String =
    queueName.stripPrefix(prefix).stripSuffix(suffix)
```

---

#### 1.2 Magic Numbers in Configuration
**Location**: `common/Logging.scala:81-83`

```scala
private val maxRetries = 5
private val initialDelayMs = 50
private val maxDelayMs = 500
```

**Recommendation**: Move to `Config` object with opaque types:

```scala
object Config.Logging:
  val maxRetries: RetryCount = RetryCount.unsafe(5)
  val initialDelayMs: Int = 50  // Or create opaque DelayMs type
  val maxDelayMs: Int = 500
```

---

#### 1.3 Hardcoded Log Directory Names
**Locations**: `common/Logging.scala:50`, `common/observability/ObservabilityConfig.scala`

```scala
private val agentLogsDir = "agent_logs"
```

**Recommendation**: All paths should come from `ObservabilityConfig`:

```scala
object ObservabilityConfig.Logs:
  val conversationDirectory: String = "conversation_logs"
  val agentDirectory: String = "agent_logs"
```

---

#### 1.4 System Prompts Hardcoded in Agents
**Location**: `examples/CodeGenMain.scala:30-45`

```scala
private val systemPrompt = """You are a Python code generator..."""
```

The `PromptRegistry` exists for prompt management and A/B testing, but agents hardcode prompts directly.

**Recommendation**: Use `PromptSource.FromRegistry`:

```scala
val GenerateCode: Process[CodeGenState, CodeGenState] =
  Process.withLlm[CodeGenState, CodeGenState](
    processName = "GenerateCode",
    promptSource = PromptSource.FromRegistry("codegen-system-prompt"),
    buildPrompt = (state, systemPrompt, _) =>
      s"$systemPrompt\n\nTask: ${state.input.taskDescription}\n\nGenerate the Python code:",
    ...
  )
```

---

#### 1.5 Hardcoded Timeout in Agent
**Location**: `examples/CodeGenMain.scala:75`

```scala
PythonInput.unsafe(state.generatedCode, timeoutSeconds = 30)
```

**Recommendation**: Use configuration:

```scala
PythonInput.unsafe(state.generatedCode, timeoutSeconds = Config.Python.executionTimeout.value)
```

---

### Category 2: FP Violations (MEDIUM Priority)

#### 2.1 Mutable State in ResultStore
**Location**: `submit/ResultStore.scala:10`

```scala
private val results = new ConcurrentHashMap[String, Result]()
```

While thread-safe, this is imperative mutable state.

**Recommendation**: Use ZIO `Ref` for referential transparency:

```scala
object ResultStore:
  type Store = Ref[Map[String, Result]]

  def make: UIO[Store] = Ref.make(Map.empty)

  def put(store: Store)(result: Result): UIO[Unit] =
    store.update(_ + (result.taskId.value -> result))

  def get(store: Store)(taskId: TaskId): UIO[Option[Result]] =
    store.get.map(_.get(taskId.value))
```

This makes state management explicit and testable.

---

#### 2.2 Blocking Thread.sleep in Retry Logic
**Location**: `common/Logging.scala:100`

```scala
Thread.sleep(delayMs)
```

This blocks the thread, preventing other work.

**Recommendation**: Since this is in non-ZIO code and logging is inherently blocking (file I/O), this is acceptable but should be documented:

```scala
// Note: Intentionally blocking - logging must complete before proceeding
Thread.sleep(delayMs)
```

If converted to ZIO, use `ZIO.sleep`:

```scala
def withRetryZIO[A](operation: Task[A]): Task[A] =
  operation.retry(
    Schedule.exponential(initialDelayMs.millis) &&
    Schedule.recurs(maxRetries)
  )
```

---

#### 2.3 Side Effects in Non-ZIO Functions
**Location**: `common/Logging.scala:159-168`

```scala
def info(conversationId: ConversationId, source: Source, agentName: String, message: String): Unit =
  printToConsole(Level.Info, Some(agentName), conversationId, message)
  val line = toJsonLine(...)
  appendToAgentLog(conversationId, agentName, line)
```

The logging functions return `Unit` and perform side effects.

**Current Approach**: Acceptable for logging infrastructure, as logging is inherently effectful.

**If Strict FP Required**: Return `ZIO[Any, Nothing, Unit]`:

```scala
def info(...): UIO[Unit] = ZIO.succeed {
  printToConsole(...)
  appendToAgentLog(...)
}
```

---

### Category 3: Type Safety Improvements (MEDIUM Priority)

#### 3.1 Loose JSON Payload Type
**Location**: `common/A2AJson.scala`

```scala
final case class A2AEnvelope(
  ...
  payload: zio.json.ast.Json  // Untyped!
)
```

**Problem**: The `payload` field loses type safety at serialization boundaries.

**Recommendation**: Consider a typed envelope pattern:

```scala
final case class TypedA2AEnvelope[A](
  fromAgent: String,
  toAgent: String,
  traceId: String,
  conversationId: String,
  payload: A
)(using JsonEncoder[A], JsonDecoder[A])
```

---

#### 3.2 Unsafe Cast in AgentBuilder
**Location**: `dsl/AgentBuilder.scala:74`

```scala
PipelineStep.identity[A].asInstanceOf[PipelineStep[A, Out]]
```

**Analysis**: This cast is safe due to type parameter constraints, but explicit casting is a code smell.

**Recommendation**: Restructure to avoid the cast using type witnesses or separate builder phases.

---

#### 3.3 Silent JSON Fallback
**Location**: `dsl/AgentRuntime.scala:318-319`

```scala
val payloadJson = zio.json.ast.Json.decoder.decodeJson(outputPayload)
  .getOrElse(zio.json.ast.Json.Str(outputPayload))
```

**Problem**: If JSON decoding fails, it silently wraps as a string. This could mask errors.

**Recommendation**: Fail explicitly or log the fallback:

```scala
val payloadJson = zio.json.ast.Json.decoder.decodeJson(outputPayload) match
  case Right(json) => json
  case Left(error) =>
    Logging.logError(convId, Source.Agent, agent.name,
      s"Payload not valid JSON, wrapping as string: $error")
    zio.json.ast.Json.Str(outputPayload)
```

---

### Category 4: Simplicity Improvements (LOW Priority)

#### 4.1 Inconsistent Opaque Type Extension Pattern
Some files use imported extensions, others use direct `.value` calls:

```scala
// Style 1 (import)
import SourceQueue.value as sqValue
inputQueue.sqValue

// Style 2 (direct)
inputQueue.value
```

**Recommendation**: Standardize on direct `.value` calls for consistency.

---

#### 4.2 RuntimeConfig Uses Primitives
**Location**: `dsl/AgentRuntime.scala:37-42`

```scala
final case class RuntimeConfig(
  prefetchCount: Int = 10,
  pollIntervalMs: Int = 100,
  connectionRetries: Int = 5,
  retryDelaySeconds: Int = 2
)
```

**Recommendation**: Use opaque types for consistency with rest of codebase:

```scala
final case class RuntimeConfig(
  prefetchCount: PrefetchCount = PrefetchCount.default,
  pollInterval: PollInterval = PollInterval.default,
  connectionRetries: RetryCount = RetryCount.unsafe(5),
  retryDelay: TimeoutSeconds = TimeoutSeconds.unsafe(2)
)
```

---

#### 4.3 Code Cleaning Regex Hardcoded
**Location**: `examples/CodeGenMain.scala:109-112`

```scala
private def cleanCode(response: String): String =
  response.trim
    .stripPrefix("```python").stripPrefix("```py").stripPrefix("```")
    .stripSuffix("```").trim
```

**Recommendation**: Extract as a configurable utility:

```scala
object CodeCleaner:
  private val codeBlockPrefixes = List("```python", "```py", "```")
  private val codeBlockSuffix = "```"

  def clean(response: String): String =
    val trimmed = response.trim
    val withoutPrefix = codeBlockPrefixes.foldLeft(trimmed)((s, p) => s.stripPrefix(p))
    withoutPrefix.stripSuffix(codeBlockSuffix).trim
```

---

## Documentation Updates Made

| File | Change |
|------|--------|
| `README.md` | Fixed project structure (removed non-existent `pipeline/`, `runners/` directories; added `conductor/`) |

---

## Priority Action Items

### Immediate (Should Fix Now)
1. **Centralize queue naming pattern** - Prevents bugs from inconsistent updates
2. **Move hardcoded prompts to PromptRegistry** - Enables A/B testing, consistent management

### Short-Term (Next Sprint)
3. **Consolidate configuration values** - All magic numbers to `Config` object
4. **Add logging for JSON fallback** - Silent failures are debugging nightmares

### Medium-Term (Technical Debt)
5. **Convert ResultStore to ZIO Ref** - Pure FP state management
6. **Standardize opaque type extension usage** - Code consistency
7. **Use opaque types in RuntimeConfig** - Type safety consistency

---

## Conclusion

The codebase exhibits strong engineering practices, particularly in type-level design. The phantom-typed `AgentBuilder` and three-way `PipelineResult` ADT are exemplary patterns that make invalid states unrepresentable.

The main areas for improvement are:
1. **Hardcoding elimination** - Several string patterns and magic numbers should be centralized
2. **FP consistency** - Some imperative patterns remain in peripheral components
3. **Type safety at boundaries** - JSON serialization loses type information

The foundation is solid. Addressing these issues will elevate the codebase from good to excellent.
