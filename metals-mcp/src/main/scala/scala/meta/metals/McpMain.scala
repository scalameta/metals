package scala.meta.metals

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.util.control.NonFatal

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.logging.MetalsLogger
import scala.meta.internal.metals.mcp.Client
import scala.meta.internal.metals.mcp.NoClient
import scala.meta.internal.metals.mcp.StandaloneMcpService
import scala.meta.io.AbsolutePath

import com.google.gson

/**
 * Entry point for standalone MCP server mode.
 *
 * This allows Metals to run as a standalone MCP (Model Context Protocol) server
 * without requiring an LSP client connection, enabling AI agents and tools to
 * interact with Scala projects directly via MCP.
 *
 * Usage:
 *   metals-mcp --workspace /path/to/project [options]
 *   Any UserConfiguration option can be passed as --key value. Booleans: --key true|false or --key (omit value = true).
 */
object McpMain {

  sealed trait Transport
  object Transport {
    case object Http extends Transport
    case object Stdio extends Transport

    def fromString(s: String): Option[Transport] = s.toLowerCase match {
      case "http" => Some(Http)
      case "stdio" => Some(Stdio)
      case _ => None
    }
  }

  case class Config(
      workspace: Option[AbsolutePath] = None,
      port: Option[Int] = None,
      transport: Transport = Transport.Http,
      client: Client = NoClient,
      userConfigOverrides: Map[String, String] = Map.empty,
  )

  private val validClients: String =
    Client.allClients.flatMap(_.names).mkString(", ")

  // Skipped config keys that do not make sense in MCP context
  private def skippedConfigKeys(key: String): Boolean = key match {
    case "start-mcp-server" => true
    case option if option.startsWith("inlay-hints.") => true
    case _ => false
  }

  /** Set of all config keys (kebab-case) for direct CLI parsing (e.g. --key value). */
  private val configKeys: Set[String] =
    UserConfiguration.options
      .map(_.key)
      .toSet
      .filterNot(skippedConfigKeys)

  /** Config keys that are boolean; when value is omitted in CLI, treated as true. */
  private val booleanConfigKeys: Set[String] =
    UserConfiguration.options
      .filter(o => o.isBoolean)
      .map(_.key)
      .toSet
      .filterNot(skippedConfigKeys)

  private val arrayConfigKeys: Set[String] =
    UserConfiguration.options
      .filter(o => o.isArray)
      .map(_.key)
      .toSet
      .filterNot(skippedConfigKeys)

  def main(args: Array[String]): Unit = {
    parseArgs(args.toList) match {
      case Left(error) =>
        System.err.println(s"Error: $error")
        printUsage()
        sys.exit(1)

      case Right(config) if config.workspace.isEmpty =>
        System.err.println("Error: --workspace is required")
        printUsage()
        sys.exit(1)

      case Right(config) =>
        runServer(config)
    }
  }

  private def parseArgs(args: List[String]): Either[String, Config] = {
    def parse(
        remaining: List[String],
        config: Config,
    ): Either[String, Config] = {
      remaining match {
        case Nil => Right(config)

        case ("--help" | "-h") :: _ =>
          printUsage()
          sys.exit(0)

        case ("--version" | "-v") :: _ =>
          println(s"metals-mcp ${BuildInfo.metalsVersion}")
          sys.exit(0)

        case "--workspace" :: path :: rest =>
          val absPath = AbsolutePath(Path.of(path).toAbsolutePath)
          if (!Files.isDirectory(absPath.toNIO)) {
            Left(s"Workspace path does not exist or is not a directory: $path")
          } else {
            parse(rest, config.copy(workspace = Some(absPath)))
          }

        case "--port" :: portStr :: rest =>
          portStr.toIntOption match {
            case Some(port) if port > 0 && port < 65536 =>
              parse(rest, config.copy(port = Some(port)))
            case _ =>
              Left(s"Invalid port number: $portStr")
          }

        case "--transport" :: transportStr :: rest =>
          Transport.fromString(transportStr) match {
            case Some(transport) =>
              parse(rest, config.copy(transport = transport))
            case None =>
              Left(
                s"Invalid transport: $transportStr. Valid options: http, stdio"
              )
          }

        case "--client" :: clientName :: rest =>
          Client.allClients.find(c => c.names.contains(clientName)) match {
            case Some(client) =>
              parse(rest, config.copy(client = client))
            case None =>
              Left(
                s"Invalid client: $clientName. Valid options: $validClients"
              )
          }

        case arg :: rest
            if arg
              .startsWith("--") && configKeys.contains(arg.stripPrefix("--")) =>
          val key = arg.stripPrefix("--")
          if (booleanConfigKeys.contains(key)) {
            val valueAndTail = rest match {
              case "true" :: t => Right(("true", t))
              case "false" :: t => Right(("false", t))
              case next :: _ if next.startsWith("--") => Right(("true", rest))
              case Nil => Right(("true", Nil))
              case _ :: _ =>
                Left(
                  s"--$key requires a boolean value (e.g. \"true\" or \"false\")"
                )
            }
            valueAndTail match {
              case Right((value, tail)) =>
                parse(
                  tail,
                  config.copy(userConfigOverrides =
                    config.userConfigOverrides + (key -> value)
                  ),
                )
              case Left(err) => Left(err)
            }
          } else {
            rest match {
              case value :: tail =>
                parse(
                  tail,
                  config.copy(userConfigOverrides =
                    config.userConfigOverrides + (key -> value)
                  ),
                )
              case Nil =>
                Left(s"--$key requires a value")
            }
          }

        case unknown :: _ =>
          Left(s"Unknown argument: $unknown")
      }
    }

    parse(args, Config())
  }

  private def printUsage(): Unit = {
    println(
      s"""
         |Metals MCP Server ${BuildInfo.metalsVersion}
         |
         |A standalone MCP (Model Context Protocol) server for Scala projects.
         |
         |Usage:
         |  metals-mcp --workspace <path> [options]
         |
         |Options:
         |  --workspace <path>      Path to the Scala project (required)
         |  --port <number>         HTTP port to listen on (default: auto-assign)
         |  --transport <type>      Transport type: http (default) or stdio (reserved for future use)
         |  --client <name>         Client to generate config for: $validClients
         |  --<key> [value]         UserConfiguration override. Use kebab-case (e.g. --java-home /path, --bloop-version 1.4.0).
         |                          For boolean options, value is optional: omit for true, or use --key true/false.
         |                          For options that accept multiple values (e.g. --excluded-packages, --scalafix-rules-dependencies,
         |                          --bloop-jvm-properties) provide a comma-separated list (e.g. --excluded-packages a.b,c.d).
         |  --help, -h              Show this help message
         |  --version, -v           Show version information
         |
         |Examples:
         |  # Start MCP server for a project
         |  metals-mcp --workspace /path/to/project
         |
         |  # Start with specific port
         |  metals-mcp --workspace /path/to/project --port 8080
         |
         |  # Override Java home and enable default BSP to build tool (boolean, omit = true)
         |  metals-mcp --workspace /path/to/project --java-home /path/to/jdk --default-bsp-to-build-tool
         |
         |  # Generate config for Cursor editor
         |  metals-mcp --workspace /path/to/project --client Cursor
         |
         |  # Start in stdio mode for direct process integration
         |  metals-mcp --workspace /path/to/project --transport stdio
         |
         |""".stripMargin
    )
  }

  private def runServer(config: Config): Unit = {
    val workspace = AbsolutePath(
      config.workspace.get.toNIO.toAbsolutePath().normalize()
    )
    val userConfig = config.userConfigOverrides match {
      case m if m.isEmpty => None
      case overrides =>
        fromStringMap(overrides) match {
          case Left(errors) =>
            System.err.println("Invalid configuration:")
            errors.foreach(e => System.err.println(e))
            sys.exit(1)
          case Right(uc) => Some(uc)
        }
    }

    val exec = Executors.newCachedThreadPool()
    implicit val ec: ExecutionContextExecutorService =
      ExecutionContext.fromExecutorService(exec)
    val sh = Executors.newSingleThreadScheduledExecutor()
    if (config.transport == Transport.Stdio) {
      val metalsLog = workspace.resolve(".metals/metals.log")
      MetalsLogger.redirectSystemOut(metalsLog)
    }

    scribe.info(
      s"Starting Metals MCP server ${BuildInfo.metalsVersion} for workspace: $workspace"
    )
    scribe.info(s"Transport: ${config.transport}")

    val service = new StandaloneMcpService(
      workspace,
      config.port,
      sh,
      config.client,
      userConfig,
    )(ec)

    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      scribe.info("Shutting down Metals MCP server...")
      try {
        service.cancel()
        ec.shutdown()
        sh.shutdown()
      } catch {
        case NonFatal(e) =>
          scribe.error("Error during shutdown", e)
      }
    }))

    try {
      service.startAndBlock()
    } catch {
      case NonFatal(e) =>
        scribe.error("Fatal error in MCP server", e)
        e.printStackTrace(System.err)
        sys.exit(1)
    }
  }

  /**
   * Build UserConfiguration from a flat key=value map (e.g. from CLI).
   * Keys use kebab-case as in [[UserConfiguration.options]]. For list options use comma-separated values.
   * Nested keys use a dot, e.g. `java-format.eclipse-config-path`.
   */
  private def fromStringMap(
      map: Map[String, String]
  ): Either[List[String], UserConfiguration] = {
    val json = new gson.JsonObject()
    for ((k, v) <- map if v != null && v.nonEmpty) {
      if (arrayConfigKeys.contains(k)) {
        val arr = new gson.JsonArray()
        v.split(",").map(_.trim).filter(_.nonEmpty).foreach(s => arr.add(s))
        json.add(k, arr)
      } else if (k.contains(".")) {
        val parts = k.split("\\.", 2)
        val parentKey = parts(0)
        val childKey = parts(1)
        val parent = Option(json.get(parentKey)) match {
          case Some(elem) => elem.getAsJsonObject
          case None => new gson.JsonObject()
        }
        parent.addProperty(childKey, v)
        json.add(parentKey, parent)
      } else {
        json.addProperty(k, v)
      }
    }
    val clientConfiguration = ClientConfiguration(MetalsServerConfig.default)
    UserConfiguration.fromJson(json, clientConfiguration)
  }
}
