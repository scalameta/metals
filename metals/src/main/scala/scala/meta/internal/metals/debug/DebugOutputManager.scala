package scala.meta.internal.metals.debug

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters._

import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.debug.OutputEventArguments

/**
 * Manages debug output storage for both active and completed debug sessions.
 * Provides in-memory caching and optional file-based persistence.
 */
class DebugOutputManager(
    workspace: AbsolutePath,
    maxInMemorySize: Int = 10000,
    persistToDisk: Boolean = true,
) {

  // In-memory storage for active sessions
  private val activeSessionOutputs =
    new ConcurrentHashMap[String, SessionOutputBuffer]()

  // Cache for recently accessed historical sessions
  private val historicalSessionCache =
    new ConcurrentHashMap[String, List[OutputEventArguments]]()

  private val debugOutputDir =
    workspace.resolve(".metals").resolve("debug-output")

  if (persistToDisk) {
    Files.createDirectories(debugOutputDir.toNIO)
  }

  /**
   * Get or create an output buffer for a session
   */
  def getOrCreateBuffer(sessionId: String): SessionOutputBuffer = {
    activeSessionOutputs.computeIfAbsent(
      sessionId,
      _ =>
        new SessionOutputBuffer(
          sessionId,
          maxInMemorySize,
          persistToDisk,
          debugOutputDir,
        ),
    )
  }

  /**
   * Add output to a session
   */
  def addOutput(sessionId: String, output: OutputEventArguments): Unit = {
    getOrCreateBuffer(sessionId).add(output)
  }

  /**
   * Get outputs for a session (active or historical)
   */
  def getOutputs(sessionId: String): Seq[OutputEventArguments] = {
    // First check active sessions
    Option(activeSessionOutputs.get(sessionId)).map(_.getAll).getOrElse {
      // Then check historical sessions
      getHistoricalOutputs(sessionId)
    }
  }

  /**
   * Mark a session as completed and optionally persist to disk
   */
  def completeSession(sessionId: String): Unit = {
    Option(activeSessionOutputs.remove(sessionId)).foreach { buffer =>
      if (persistToDisk) {
        buffer.forcePersistToDisk()
      }
    }
  }

  /**
   * Get outputs from a historical session
   */
  private def getHistoricalOutputs(
      sessionId: String
  ): Seq[OutputEventArguments] = {
    if (!persistToDisk) return Seq.empty

    // Check cache first
    Option(historicalSessionCache.get(sessionId)).getOrElse {
      val outputFile = debugOutputDir.resolve(s"$sessionId.log").toNIO
      if (Files.exists(outputFile)) {
        val outputs = Files
          .readAllLines(outputFile, StandardCharsets.UTF_8)
          .asScala
          .map { line =>
            // Parse the stored format: [category] output
            val categoryEnd = line.indexOf(']')
            if (categoryEnd > 0 && line.startsWith("[")) {
              val category = line.substring(1, categoryEnd)
              val text = line.substring(categoryEnd + 2) // Skip "] "
              val output = new OutputEventArguments()
              output.setCategory(category)
              output.setOutput(text + "\n") // Re-add newline
              output
            } else {
              // Fallback for malformed lines
              val output = new OutputEventArguments()
              output.setCategory("stdout")
              output.setOutput(line + "\n")
              output
            }
          }
          .toList

        // Cache for future access
        historicalSessionCache.put(sessionId, outputs)
        outputs
      } else {
        Seq.empty
      }
    }
  }

  /**
   * List all available sessions (active and historical)
   */
  def listSessions(): List[String] = {
    val activeSessions = activeSessionOutputs.keys().asScala.toList
    val historicalSessions =
      if (persistToDisk && Files.exists(debugOutputDir.toNIO)) {
        Files
          .list(debugOutputDir.toNIO)
          .iterator()
          .asScala
          .filter(_.toString.endsWith(".log"))
          .map(_.getFileName.toString.stripSuffix(".log"))
          .toList
      } else {
        List.empty
      }
    (activeSessions ++ historicalSessions).distinct.sorted
  }

  /**
   * Clean up old session files
   */
  def cleanup(keepDays: Int = 7): Unit = {
    if (!persistToDisk) return

    val cutoffTime =
      System.currentTimeMillis() - (keepDays * 24 * 60 * 60 * 1000L)

    if (Files.exists(debugOutputDir.toNIO)) {
      Files
        .list(debugOutputDir.toNIO)
        .iterator()
        .asScala
        .filter(_.toString.endsWith(".log"))
        .filter(path => Files.getLastModifiedTime(path).toMillis < cutoffTime)
        .foreach { path =>
          Files.delete(path)
          // Also remove from cache
          val sessionId = path.getFileName.toString.stripSuffix(".log")
          historicalSessionCache.remove(sessionId)
        }
    }
  }
}

/**
 * Buffer for storing outputs of a single debug session
 */
class SessionOutputBuffer(
    sessionId: String,
    maxSize: Int,
    persistToDisk: Boolean,
    outputDir: AbsolutePath,
) {
  private val outputs = new ListBuffer[OutputEventArguments]()
  private val lock = new Object()

  def add(output: OutputEventArguments): Unit = lock.synchronized {
    outputs += output
    // Keep only the most recent maxSize entries
    if (outputs.size > maxSize) {
      outputs.remove(0)
    }

    // Optionally write to disk immediately
    if (persistToDisk) {
      appendToDisk(output)
    }
  }

  def getAll: Seq[OutputEventArguments] = lock.synchronized {
    outputs.toSeq
  }

  private def appendToDisk(output: OutputEventArguments): Unit = {
    val outputFile = outputDir.resolve(s"$sessionId.log").toNIO
    val category = Option(output.getCategory).getOrElse("unknown")
    val text = Option(output.getOutput).getOrElse("")
    val line = s"[$category] $text"

    Files.write(
      outputFile,
      line.getBytes(StandardCharsets.UTF_8),
      StandardOpenOption.CREATE,
      StandardOpenOption.APPEND,
    )
  }

  def forcePersistToDisk(): Unit = {
    // Already persisted if persistToDisk is true
    if (!persistToDisk) {
      lock.synchronized {
        val outputFile = outputDir.resolve(s"$sessionId.log").toNIO
        val lines = outputs.map { output =>
          val category = Option(output.getCategory).getOrElse("unknown")
          val text = Option(output.getOutput).getOrElse("")
          s"[$category] $text"
        }.asJava

        Files.write(outputFile, lines, StandardCharsets.UTF_8)
      }
    }
  }
}
