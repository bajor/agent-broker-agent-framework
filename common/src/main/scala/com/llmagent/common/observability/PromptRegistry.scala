package com.llmagent.common.observability

import java.sql.{Connection, DriverManager, ResultSet}
import java.time.Instant
import scala.util.{Random, Using}
import Types.*
import PromptId.value as promptIdValue
import VersionId.value as versionIdValue

/** Read-only registry for prompts and versions stored in prompts.db */
object PromptRegistry {

  private val dbPath = ObservabilityConfig.Database.promptsDb
  private val random = new Random()

  private def getConnection(): Connection = {
    Class.forName("org.sqlite.JDBC")
    DriverManager.getConnection(s"jdbc:sqlite:$dbPath")
  }

  /** Get a prompt by name */
  def getPromptByName(name: String): PromptResult[Prompt] = {
    try {
      Using.resource(getConnection()) { conn =>
        Using.resource(conn.prepareStatement(
          "SELECT id, name, description, created_at FROM prompts WHERE name = ?"
        )) { stmt =>
          stmt.setString(1, name)
          Using.resource(stmt.executeQuery()) { rs =>
            if (rs.next()) {
              PromptResult.Success(rowToPrompt(rs))
            } else {
              PromptResult.NotFound(s"Prompt not found: $name")
            }
          }
        }
      }
    } catch {
      case e: Exception => PromptResult.Error(e.getMessage)
    }
  }

  /** Get a prompt by ID */
  def getPromptById(id: PromptId): PromptResult[Prompt] = {
    try {
      Using.resource(getConnection()) { conn =>
        Using.resource(conn.prepareStatement(
          "SELECT id, name, description, created_at FROM prompts WHERE id = ?"
        )) { stmt =>
          stmt.setString(1, id.promptIdValue)
          Using.resource(stmt.executeQuery()) { rs =>
            if (rs.next()) {
              PromptResult.Success(rowToPrompt(rs))
            } else {
              PromptResult.NotFound(s"Prompt not found: ${id.promptIdValue}")
            }
          }
        }
      }
    } catch {
      case e: Exception => PromptResult.Error(e.getMessage)
    }
  }

  /** Get all enabled versions for a prompt */
  def getEnabledVersions(promptId: PromptId): PromptResult[List[PromptVersion]] = {
    try {
      Using.resource(getConnection()) { conn =>
        Using.resource(conn.prepareStatement(
          "SELECT id, prompt_id, version, content, enabled, created_at FROM prompt_versions WHERE prompt_id = ? AND enabled = 1"
        )) { stmt =>
          stmt.setString(1, promptId.promptIdValue)
          Using.resource(stmt.executeQuery()) { rs =>
            val versions = scala.collection.mutable.ListBuffer[PromptVersion]()
            while (rs.next()) {
              versions += rowToVersion(rs)
            }
            PromptResult.Success(versions.toList)
          }
        }
      }
    } catch {
      case e: Exception => PromptResult.Error(e.getMessage)
    }
  }

  /** Get a random enabled version for a prompt (for A/B testing) */
  def getRandomEnabledVersion(promptId: PromptId): PromptResult[PromptVersion] = {
    getEnabledVersions(promptId) match {
      case PromptResult.Success(versions) if versions.nonEmpty =>
        val idx = random.nextInt(versions.size)
        PromptResult.Success(versions(idx))
      case PromptResult.Success(_) =>
        PromptResult.NotFound(s"No enabled versions for prompt: ${promptId.promptIdValue}")
      case PromptResult.NotFound(msg) => PromptResult.NotFound(msg)
      case PromptResult.Error(msg) => PromptResult.Error(msg)
    }
  }

  /** Get a random enabled version by prompt name */
  def getRandomEnabledVersionByName(promptName: String): PromptResult[PromptVersion] = {
    getPromptByName(promptName) match {
      case PromptResult.Success(prompt) => getRandomEnabledVersion(prompt.id)
      case PromptResult.NotFound(msg) => PromptResult.NotFound(msg)
      case PromptResult.Error(msg) => PromptResult.Error(msg)
    }
  }

  /** Get a specific version by ID */
  def getVersionById(id: VersionId): PromptResult[PromptVersion] = {
    try {
      Using.resource(getConnection()) { conn =>
        Using.resource(conn.prepareStatement(
          "SELECT id, prompt_id, version, content, enabled, created_at FROM prompt_versions WHERE id = ?"
        )) { stmt =>
          stmt.setString(1, id.versionIdValue)
          Using.resource(stmt.executeQuery()) { rs =>
            if (rs.next()) {
              PromptResult.Success(rowToVersion(rs))
            } else {
              PromptResult.NotFound(s"Version not found: ${id.versionIdValue}")
            }
          }
        }
      }
    } catch {
      case e: Exception => PromptResult.Error(e.getMessage)
    }
  }

  /** List all prompts */
  def listPrompts(): PromptResult[List[Prompt]] = {
    try {
      Using.resource(getConnection()) { conn =>
        Using.resource(conn.createStatement()) { stmt =>
          Using.resource(stmt.executeQuery("SELECT id, name, description, created_at FROM prompts ORDER BY name")) { rs =>
            val prompts = scala.collection.mutable.ListBuffer[Prompt]()
            while (rs.next()) {
              prompts += rowToPrompt(rs)
            }
            PromptResult.Success(prompts.toList)
          }
        }
      }
    } catch {
      case e: Exception => PromptResult.Error(e.getMessage)
    }
  }

  private def rowToPrompt(rs: ResultSet): Prompt = {
    Prompt(
      id = PromptId.fromString(rs.getString("id")).get,
      name = rs.getString("name"),
      description = rs.getString("description"),
      createdAt = Instant.parse(rs.getString("created_at"))
    )
  }

  private def rowToVersion(rs: ResultSet): PromptVersion = {
    PromptVersion(
      id = VersionId.fromString(rs.getString("id")).get,
      promptId = PromptId.fromString(rs.getString("prompt_id")).get,
      version = rs.getString("version"),
      content = rs.getString("content"),
      enabled = rs.getInt("enabled") == 1,
      createdAt = Instant.parse(rs.getString("created_at"))
    )
  }
}
