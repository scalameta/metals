package scala.meta.internal.metals.mbt

import java.io.BufferedOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

import scala.util.Using
import scala.util.control.NonFatal

import scala.meta.internal.metals.Configs.TurbineCacheConfig
import scala.meta.internal.metals.Configs.TurbineRecompileDelayConfig
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.Timer
import scala.meta.io.AbsolutePath

import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.common.hash.Hashing
import com.google.turbine.binder.ClassPathBinder
import com.google.turbine.binder.sym.ClassSymbol
import com.google.turbine.lower.Lower
import com.google.turbine.zip.Zip

/**
 * Handles caching of Turbine compilation results to disk.
 *
 * The cache is stored as a JAR file containing the compiled class files.
 * Each class file is stored under its binary name with a .class extension.
 * The cache is keyed by the current git HEAD hash to ensure it's invalidated
 * when the source revision changes.
 *
 * @param cachePath Path to the cache JAR file
 * @param cacheConfig Configuration for caching behavior
 * @param recompileDelayConfig Configuration for recompile delay (to check if turbine is disabled)
 */
class TurbineCache(
    workspace: AbsolutePath,
    cacheConfig: () => TurbineCacheConfig,
    recompileDelayConfig: () => TurbineRecompileDelayConfig,
    time: Time,
) {
  private val cachePath = workspace.resolve(Directories.turbineCache)
  private val CacheKeyEntry = "META-INF/turbine-cache-key"

  // we need to always compile on start
  private def isCacheEnabled: Boolean = {
    val config = cacheConfig()
    val recompileConfig = recompileDelayConfig()
    config.enabled && !recompileConfig.isEffectivelyDisabled
  }

  /**
   * Writes the Turbine compilation result to the cache file.
   * Uses the current git hash as the cache key.
   *
   * @param result The compilation result to cache
   */
  def writeCache(result: TurbineCompileResult): Unit =
    if (isCacheEnabled) {
      val timer = new Timer(time)
      try {
        val bytes = result.lowered.bytes()
        if (bytes.isEmpty()) {
          scribe.debug("turbine-cache: skipping write, no classes to cache")
        } else {
          GitVCS.getHeadHash(workspace) match {
            case Some(gitHash) =>
              cachePath.parent.createDirectories()
              Using.resource(
                new JarOutputStream(
                  new BufferedOutputStream(
                    Files.newOutputStream(
                      cachePath.toNIO,
                      StandardOpenOption.CREATE,
                      StandardOpenOption.TRUNCATE_EXISTING,
                    )
                  )
                )
              ) { jos =>
                // Store the git hash as cache key
                addEntry(
                  jos,
                  CacheKeyEntry,
                  gitHash.getBytes(StandardCharsets.UTF_8),
                )
                bytes.forEach { (binaryName, classBytes) =>
                  addEntry(jos, binaryName + ".class", classBytes)
                }
              }

              scribe.info(
                s"turbine-cache: wrote ${result.lowered.symbols().size()} classes in ${timer.elapsedMillis}ms (git: ${gitHash.take(8)})"
              )
            case None =>
              scribe.debug(
                "turbine-cache: skipping write, not in a git repository"
              )
          }
        }
      } catch {
        case NonFatal(e) =>
          scribe.warn(s"turbine-cache: failed to write cache: ${e.getMessage}")
      }
    }

  /**
   * Reads the cached Turbine compilation result from disk.
   * Validates that the stored git hash matches the current HEAD.
   *
   * @param classpath The classpath to bind for dependency resolution
   * @return The cached result, or None if cache doesn't exist, is invalid, or git hash mismatches
   */
  def readCache(classpath: Seq[Path]): Option[TurbineCompileResult] = {
    lazy val expectedHash = GitVCS.getHeadHash(workspace)
    if (!isCacheEnabled) {
      scribe.debug("turbine-cache: caching is disabled")
      None
    } else if (!cachePath.exists) {
      scribe.debug("turbine-cache: no cache file found")
      None
    } else if (expectedHash.isEmpty) {
      scribe.warn("turbine-cache: not in a git repository, skipping cache")
      None
    } else {
      val timer = new Timer(time)
      try {
        val bytesBuilder = ImmutableMap.builder[String, Array[Byte]]()
        val symbolsBuilder = ImmutableSet.builder[ClassSymbol]()
        var storedHash: Option[String] = None
        Using.resource(new Zip.ZipIterable(cachePath.toNIO)) { zipIterable =>
          zipIterable.forEach { entry =>
            val name = entry.name()
            if (name == CacheKeyEntry) {
              storedHash =
                Some(new String(entry.data(), StandardCharsets.UTF_8))
            } else if (name.endsWith(".class")) {
              val binaryName = name.stripSuffix(".class")
              val sym = new ClassSymbol(binaryName)
              symbolsBuilder.add(sym)
              bytesBuilder.put(binaryName, entry.data())
            }
          }
        }

        // Validate the git hash
        storedHash match {
          case Some(hash) if hash == expectedHash.get =>
            val lowered = Lower.Lowered.create(
              bytesBuilder.build(),
              symbolsBuilder.build(),
            )
            // Bind the project classpath (libraries) so dependency symbols remain
            // discoverable when serving classes from the cached lowered output.
            val classPath = ClassPathBinder.bindClasspath(classpath.asJava)
            val result = TurbineCompileResult(classPath, lowered)

            scribe.info(
              s"turbine-cache: loaded ${lowered.symbols().size()} classes in ${timer.elapsedMillis}ms (git: ${expectedHash.get.take(8)})"
            )
            Some(result)
          case Some(hash) =>
            scribe.info(
              s"turbine-cache: git hash mismatch, invalidating cache (stored=${hash.take(8)}, current=${expectedHash.get.take(8)})"
            )
            deleteCache()
            None
          case None =>
            scribe.info(
              "turbine-cache: no git hash found in cache, invalidating"
            )
            deleteCache()
            None
        }

      } catch {
        case NonFatal(e) =>
          scribe.warn(s"turbine-cache: failed to read cache: ${e.getMessage}")
          deleteCache()
          None
      }
    }
  }

  /**
   * Deletes the cache file if it exists.
   */
  def deleteCache(): Unit = {
    try {
      cachePath.deleteIfExists()
      scribe.debug("turbine-cache: deleted cache file")
    } catch {
      case NonFatal(e) =>
        scribe.warn(s"turbine-cache: failed to delete cache: ${e.getMessage}")
    }
  }

  private val DEFAULT_TIMESTAMP: LocalDateTime =
    LocalDateTime.of(2010, 1, 1, 0, 0, 0)

  private def addEntry(
      jos: JarOutputStream,
      name: String,
      bytes: Array[Byte],
  ): Unit = {
    val entry = new JarEntry(name)
    entry.setTimeLocal(DEFAULT_TIMESTAMP)
    entry.setMethod(ZipEntry.STORED)
    entry.setSize(bytes.length)
    entry.setCrc(Hashing.crc32().hashBytes(bytes).padToLong())
    jos.putNextEntry(entry)
    jos.write(bytes)
  }
}
