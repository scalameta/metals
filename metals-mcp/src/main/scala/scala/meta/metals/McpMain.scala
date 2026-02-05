package scala.meta.metals

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.util.control.NonFatal

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.logging.MetalsLogger
import scala.meta.internal.metals.mcp.StandaloneMcpService
import scala.meta.io.AbsolutePath

/**
 * Entry point for standalone MCP server mode.
 *
 * This allows Metals to run as a standalone MCP (Model Context Protocol) server
 * without requiring an LSP client connection, enabling AI agents and tools to
 * interact with Scala projects directly via MCP.
 *
 * Usage:
 *   metals-mcp --workspace /path/to/project [--port <number>] [--transport <stdio|http>]
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
  )

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
         |  # Start in stdio mode for direct process integration
         |  metals-mcp --workspace /path/to/project --transport stdio
         |
         |""".stripMargin
    )
  }

  private def runServer(config: Config): Unit = {
    val workspace = config.workspace.get
    val exec = Executors.newCachedThreadPool()
    implicit val ec: ExecutionContextExecutorService =
      ExecutionContext.fromExecutorService(exec)
    val sh = Executors.newSingleThreadScheduledExecutor()
    val metalsLog = workspace.resolve(".metals/metals.log")
    MetalsLogger.redirectSystemOut(metalsLog)

    scribe.info(
      s"Starting Metals MCP server ${BuildInfo.metalsVersion} for workspace: $workspace"
    )
    scribe.info(s"Transport: ${config.transport}")

    val service = new StandaloneMcpService(
      workspace,
      config.port,
      sh,
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
}
