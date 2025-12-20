package com.llmagent.common

import java.net.{HttpURLConnection, URI}
import java.io.{BufferedReader, InputStreamReader, OutputStreamWriter}
import scala.util.Using
import com.llmagent.common.observability.Types.ConversationId

/** HTTP client for Ollama LLM - minimal implementation using standard library */
object LlmClient:

  /** Query result - sealed to ensure exhaustive handling */
  enum QueryResult:
    case Success(response: String)
    case Failure(error: String)

  /** Query the Ollama LLM with a prompt (logging handled by ObservableLlmClient) */
  def query(prompt: String, model: String = Config.Ollama.defaultModel): QueryResult =
    val url = s"${Config.Ollama.baseUrl}${Config.Ollama.generateEndpoint}"

    try
      val connection = URI.create(url).toURL.openConnection().asInstanceOf[HttpURLConnection]
      connection.setRequestMethod("POST")
      connection.setRequestProperty("Content-Type", "application/json")
      connection.setConnectTimeout(Config.Timing.connectionTimeout.value * 1000)
      connection.setReadTimeout(Config.Timing.requestTimeout.value * 1000)
      connection.setDoOutput(true)

      val requestBody = Messages.Json.encodeOllamaRequest(prompt, model)

      Using.resource(new OutputStreamWriter(connection.getOutputStream, "UTF-8")) { writer =>
        writer.write(requestBody)
        writer.flush()
      }

      val responseCode = connection.getResponseCode

      if responseCode == 200 then
        val response = Using.resource(new BufferedReader(new InputStreamReader(connection.getInputStream, "UTF-8"))) { reader =>
          val sb = new StringBuilder
          var line = reader.readLine()
          while line != null do
            sb.append(line)
            line = reader.readLine()
          sb.toString
        }

        Messages.Json.decodeOllamaResponse(response) match
          case Some(text) =>
            QueryResult.Success(text)
          case None =>
            QueryResult.Failure(s"Failed to parse Ollama response: $response")
      else
        QueryResult.Failure(s"Ollama returned status $responseCode")
    catch
      case e: java.net.ConnectException =>
        QueryResult.Failure(s"Cannot connect to Ollama at $url - is Ollama running?")
      case e: java.net.SocketTimeoutException =>
        QueryResult.Failure(s"Ollama request timed out after ${Config.Timing.requestTimeout.value} seconds")
      case e: Exception =>
        QueryResult.Failure(s"LLM query failed: ${e.getMessage}")
