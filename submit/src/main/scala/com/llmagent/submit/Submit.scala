package com.llmagent.submit

import com.llmagent.common.*
import com.llmagent.common.Messages.*
import com.llmagent.common.observability.Types as ObsTypes
import com.rabbitmq.client.{Channel, Connection}
import java.util.concurrent.atomic.AtomicBoolean
import scala.io.StdIn

/** Submit service for task submission and result retrieval */
object Submit:

  private val running = new AtomicBoolean(true)

  /** System conversation ID for infrastructure logs */
  private val systemConversationId: ObsTypes.ConversationId =
    ObsTypes.ConversationId.unsafe("system-submit")

  /** Setup signal handlers for graceful shutdown */
  private def setupSignalHandlers(): Unit =
    val shutdownHook = new Thread(() => {
      Logging.info(systemConversationId, Logging.Source.Submit, "Shutdown signal received...")
      running.set(false)
    })
    Runtime.getRuntime.addShutdownHook(shutdownHook)

  /** Print help message */
  private def printHelp(): Unit =
    println("""
      |LLM Agent Submit Service
      |========================
      |Commands:
      |  submit <prompt>  - Submit a task to the LLM
      |  status <id>      - Check status of a task by ID
      |  list             - List all received results
      |  help             - Show this help message
      |  quit             - Exit the program
      |""".stripMargin)

  /** Handle submit command */
  private def handleSubmit(channel: Channel, args: String): Unit =
    if args.trim.isEmpty then
      println("Error: Please provide a prompt")
      return

    val task = Task.create(args.trim)
    val json = Json.encodeTask(task)
    val conversationId = ObsTypes.ConversationId.fromString(task.id.value).getOrElse(systemConversationId)

    RabbitMQ.publish(channel, Config.Queues.taskQueue, json, conversationId) match
      case RabbitMQ.PublishResult.Published =>
        println(s"Task submitted with ID: ${task.id.value}")
        println("Use 'status <id>' to check the result")
      case RabbitMQ.PublishResult.Failed(error) =>
        println(s"Error: Failed to submit task - $error")

  /** Handle status command */
  private def handleStatus(args: String): Unit =
    val taskId = args.trim
    if taskId.isEmpty then
      println("Error: Please provide a task ID")
      return

    ResultStore.getByString(taskId) match
      case Some(result) =>
        result.status match
          case ResultStatus.Success(response) =>
            println(s"Task ${result.taskId.value} - SUCCESS")
            println(s"Response:\n$response")
          case ResultStatus.Failure(error) =>
            println(s"Task ${result.taskId.value} - FAILURE")
            println(s"Error: $error")
      case None =>
        println(s"No result found for task $taskId")
        println("The task may still be processing or the ID may be incorrect")

  /** Handle list command */
  private def handleList(): Unit =
    val results = ResultStore.all
    if results.isEmpty then
      println("No results available")
    else
      println(s"Results (${results.size}):")
      results.foreach { result =>
        val status = result.status match
          case ResultStatus.Success(_) => "SUCCESS"
          case ResultStatus.Failure(_) => "FAILURE"
        println(s"  ${result.taskId.value} - $status")
      }

  /** Process a single command */
  private def processCommand(channel: Channel, input: String): Boolean =
    val parts = input.trim.split("\\s+", 2)
    val command = parts.headOption.getOrElse("").toLowerCase
    val args = parts.lift(1).getOrElse("")

    command match
      case "submit" =>
        handleSubmit(channel, args)
        true
      case "status" =>
        handleStatus(args)
        true
      case "list" =>
        handleList()
        true
      case "help" =>
        printHelp()
        true
      case "quit" | "exit" | "q" =>
        false
      case "" =>
        true
      case _ =>
        println(s"Unknown command: $command")
        println("Type 'help' for available commands")
        true

  /** Main command loop */
  private def commandLoop(channel: Channel): Unit =
    printHelp()
    print("> ")

    while running.get() do
      val input = StdIn.readLine()
      if input == null then
        running.set(false)
      else if !processCommand(channel, input) then
        running.set(false)
      else
        print("> ")

  /** Run the submit service */
  def run(): Unit =
    Logging.info(systemConversationId, Logging.Source.Submit, "LLM Agent Submit Service starting...")
    setupSignalHandlers()

    // Start the result listener
    ResultListener.start()

    // Connect to RabbitMQ for submitting tasks
    RabbitMQ.connect(systemConversationId) match
      case RabbitMQ.ConnectionResult.Connected(connection, channel) =>
        try
          RabbitMQ.declareQueue(channel, Config.Queues.taskQueue)
          Logging.info(systemConversationId, Logging.Source.Submit, "Connected to task queue")
          commandLoop(channel)
        finally
          Logging.info(systemConversationId, Logging.Source.Submit, "Shutting down...")
          ResultListener.stop()
          RabbitMQ.close(connection, channel)
          Logging.info(systemConversationId, Logging.Source.Submit, "Submit service stopped")

      case RabbitMQ.ConnectionResult.Failed(error) =>
        Logging.logError(systemConversationId, Logging.Source.Submit, s"Failed to connect to RabbitMQ: $error")
        ResultListener.stop()
        System.exit(1)
