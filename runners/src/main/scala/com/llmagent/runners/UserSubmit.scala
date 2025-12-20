package com.llmagent.runners

import com.llmagent.common.*
import com.llmagent.common.Agent.AgentId
import com.llmagent.common.observability.Types as ObsTypes
import zio.*
import java.util.UUID

/** User Submit CLI - sends prompts to the distributed pipeline
  *
  * Usage: UserSubmit [prompt-key]
  *
  * Available prompts:
  *   - fibonacci (default)
  *   - prime
  *   - factorial
  *   - sort
  *   - palindrome
  */
object UserSubmit extends ZIOAppDefault:

  private val systemConversationId: ObsTypes.ConversationId =
    ObsTypes.ConversationId.unsafe("system-user-submit")

  private val inputQueue = s"agent_${AgentNames.preprocessor}_tasks"

  private val examplePrompts: List[(String, String)] = List(
    "fibonacci"  -> "Calculate the first 15 Fibonacci numbers and print them as a comma-separated list.",
    "prime"      -> "Find all prime numbers between 1 and 50 and print them.",
    "factorial"  -> "Calculate the factorial of 10 and print the result.",
    "sort"       -> "Sort the list [64, 34, 25, 12, 22, 11, 90] using bubble sort and print the sorted result.",
    "palindrome" -> "Check if 'racecar', 'hello', and 'madam' are palindromes and print the results."
  )

  private def connect: ZIO[Any, Throwable, (com.rabbitmq.client.Connection, com.rabbitmq.client.Channel)] =
    ZIO.attempt {
      RabbitMQ.connect(systemConversationId) match
        case RabbitMQ.ConnectionResult.Connected(conn, chan) => (conn, chan)
        case RabbitMQ.ConnectionResult.Failed(error) =>
          throw new RuntimeException(s"Failed to connect: $error")
    }

  private def submitPrompt(
    channel: com.rabbitmq.client.Channel,
    prompt: String
  ): ZIO[Any, Throwable, (ObsTypes.ConversationId, String)] =
    ZIO.attempt {
      import ObsTypes.ConversationId.value as convIdValue

      val conversationId = ObsTypes.ConversationId.fromString(UUID.randomUUID().toString).get
      val traceId = UUID.randomUUID().toString
      val userInput = UserInput(rawPrompt = prompt, metadata = Map.empty)

      val envelope = A2AJson.createEnvelope(
        from = AgentId.unsafe("__user__"),
        to = AgentNames.preprocessor,
        traceId = traceId,
        conversationId = conversationId,
        payload = userInput,
        encoder = A2AJson.encodeUserInput,
        payloadType = A2AJson.PayloadTypes.UserInput
      )

      RabbitMQ.declareQueue(channel, inputQueue)

      RabbitMQ.publish(channel, inputQueue, envelope, conversationId) match
        case RabbitMQ.PublishResult.Published =>
          (conversationId, traceId)
        case RabbitMQ.PublishResult.Failed(error) =>
          throw new RuntimeException(s"Failed to publish: $error")
    }

  override def run: ZIO[ZIOAppArgs, Any, ExitCode] =
    import ObsTypes.ConversationId.value as convIdValue

    for
      args <- getArgs
      promptKey = args.headOption.getOrElse("fibonacci")

      prompt <- ZIO.fromOption(examplePrompts.find(_._1 == promptKey).map(_._2))
        .mapError { _ =>
          val available = examplePrompts.map(_._1).mkString(", ")
          println(s"Unknown prompt key: $promptKey")
          println(s"Available prompts: $available")
        }

      sep = "=" * 70
      _ <- ZIO.succeed(println(s"\n$sep\nUSER SUBMIT - Distributed Pipeline\n$sep\nPrompt Key: $promptKey\nRequest: $prompt\n$sep\n"))

      result <- (for
        connChan <- connect
        (connection, channel) = connChan
        ids <- submitPrompt(channel, prompt)
        (conversationId, traceId) = ids
        _ <- ZIO.succeed {
          println(s"\nSubmitted successfully!\n\n  Conversation ID: ${conversationId.convIdValue}\n  Trace ID:        $traceId\n  Target Queue:    $inputQueue\n\nWatch the RefinerRunner output for the final response.\n$sep\n")
          RabbitMQ.close(connection, channel)
        }
      yield ExitCode.success).catchAll { e =>
        ZIO.succeed {
          println(s"Error: ${e.getMessage}")
          ExitCode.failure
        }
      }
    yield result
