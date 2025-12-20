package com.llmagent.common.guardrails

import java.sql.{Connection, DriverManager, ResultSet}
import java.time.Instant
import scala.util.Using
import com.llmagent.common.Types.QueryResult
import com.llmagent.common.observability.ObservabilityConfig
import Types.*

/**
 * Read-only registry for guardrails stored in guardrails.db
 *
 * Database schema:
 * CREATE TABLE pipelines (
 *   id TEXT PRIMARY KEY,
 *   name TEXT NOT NULL UNIQUE,
 *   description TEXT NOT NULL,
 *   allowed_scope TEXT NOT NULL,
 *   created_at TEXT NOT NULL
 * );
 *
 * CREATE TABLE guardrails (
 *   id TEXT PRIMARY KEY,
 *   pipeline_id TEXT NOT NULL REFERENCES pipelines(id),
 *   name TEXT NOT NULL,
 *   description TEXT NOT NULL,
 *   check_prompt TEXT NOT NULL,
 *   enabled INTEGER NOT NULL DEFAULT 1,
 *   created_at TEXT NOT NULL
 * );
 */
object GuardrailsRegistry:

  private val dbPath = ObservabilityConfig.Database.guardrailsDb

  private def getConnection(): Connection =
    Class.forName("org.sqlite.JDBC")
    DriverManager.getConnection(s"jdbc:sqlite:$dbPath")

  /** Get pipeline safety config by name */
  def getSafetyConfigByName(name: String): QueryResult[PipelineSafetyConfig] =
    try
      Using.resource(getConnection()) { conn =>
        Using.resource(conn.prepareStatement(
          "SELECT id, name, description, allowed_scope, created_at FROM pipelines WHERE name = ?"
        )) { stmt =>
          stmt.setString(1, name)
          Using.resource(stmt.executeQuery()) { rs =>
            if rs.next() then
              QueryResult.Success(rowToSafetyConfig(rs))
            else
              QueryResult.NotFound(s"Pipeline safety config not found: $name")
          }
        }
      }
    catch
      case e: Exception => QueryResult.Error(e.getMessage)

  /** Get pipeline safety config by ID */
  def getSafetyConfigById(id: SafetyConfigId): QueryResult[PipelineSafetyConfig] =
    import SafetyConfigId.value
    try
      Using.resource(getConnection()) { conn =>
        Using.resource(conn.prepareStatement(
          "SELECT id, name, description, allowed_scope, created_at FROM pipelines WHERE id = ?"
        )) { stmt =>
          stmt.setString(1, id.value)
          Using.resource(stmt.executeQuery()) { rs =>
            if rs.next() then
              QueryResult.Success(rowToSafetyConfig(rs))
            else
              QueryResult.NotFound(s"Pipeline safety config not found: ${id.value}")
          }
        }
      }
    catch
      case e: Exception => QueryResult.Error(e.getMessage)

  /** Get all enabled guardrails for a pipeline */
  def getEnabledGuardrails(safetyConfigId: SafetyConfigId): QueryResult[List[Guardrail]] =
    import SafetyConfigId.value
    try
      Using.resource(getConnection()) { conn =>
        Using.resource(conn.prepareStatement(
          """SELECT id, pipeline_id, name, description, check_prompt, enabled, created_at
             FROM guardrails WHERE pipeline_id = ? AND enabled = 1"""
        )) { stmt =>
          stmt.setString(1, safetyConfigId.value)
          Using.resource(stmt.executeQuery()) { rs =>
            val guardrails = scala.collection.mutable.ListBuffer[Guardrail]()
            while rs.next() do
              guardrails += rowToGuardrail(rs)
            QueryResult.Success(guardrails.toList)
          }
        }
      }
    catch
      case e: Exception => QueryResult.Error(e.getMessage)

  /** Get enabled guardrails by pipeline name */
  def getEnabledGuardrailsByPipelineName(pipelineName: String): QueryResult[List[Guardrail]] =
    getSafetyConfigByName(pipelineName) match
      case QueryResult.Success(config) => getEnabledGuardrails(config.id)
      case QueryResult.NotFound(msg) => QueryResult.NotFound(msg)
      case QueryResult.Error(msg) => QueryResult.Error(msg)

  /** List all pipeline safety configs */
  def listSafetyConfigs(): QueryResult[List[PipelineSafetyConfig]] =
    try
      Using.resource(getConnection()) { conn =>
        Using.resource(conn.createStatement()) { stmt =>
          Using.resource(stmt.executeQuery(
            "SELECT id, name, description, allowed_scope, created_at FROM pipelines ORDER BY name"
          )) { rs =>
            val configs = scala.collection.mutable.ListBuffer[PipelineSafetyConfig]()
            while rs.next() do
              configs += rowToSafetyConfig(rs)
            QueryResult.Success(configs.toList)
          }
        }
      }
    catch
      case e: Exception => QueryResult.Error(e.getMessage)

  private def rowToSafetyConfig(rs: ResultSet): PipelineSafetyConfig =
    PipelineSafetyConfig(
      id = SafetyConfigId.fromString(rs.getString("id")).get,
      name = rs.getString("name"),
      description = rs.getString("description"),
      allowedScope = rs.getString("allowed_scope"),
      createdAt = Instant.parse(rs.getString("created_at"))
    )

  private def rowToGuardrail(rs: ResultSet): Guardrail =
    Guardrail(
      id = GuardrailId.fromString(rs.getString("id")).get,
      safetyConfigId = SafetyConfigId.fromString(rs.getString("pipeline_id")).get,
      name = rs.getString("name"),
      description = rs.getString("description"),
      checkPrompt = rs.getString("check_prompt"),
      enabled = rs.getInt("enabled") == 1,
      createdAt = Instant.parse(rs.getString("created_at"))
    )
