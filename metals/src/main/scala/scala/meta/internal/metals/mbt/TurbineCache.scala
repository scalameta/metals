package scala.meta.internal.metals.mbt

import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

import scala.util.Using
import scala.util.control.NonFatal

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.Configs.TurbineCacheConfig
import scala.meta.internal.metals.Configs.TurbineRecompileDelayConfig
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.Timer

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
 *
 * @param cachePath Path to the cache JAR file
 * @param cacheConfig Configuration for caching behavior
 * @param recompileDelayConfig Configuration for recompile delay (to check if turbine is disabled)
 */
class TurbineCache(
    cachePath: Path,
    cacheConfig: () => TurbineCacheConfig,
    recompileDelayConfig: () => TurbineRecompileDelayConfig,
    time: Time,
) {

  // we need to always compile on start
  private def isCacheEnabled: Boolean = {
    val config = cacheConfig()
    val recompileConfig = recompileDelayConfig()
    config.enabled && !recompileConfig.isEffectivelyDisabled
  }

  /**
   * Writes the Turbine compilation result to the cache file.
   *
   * @param result The compilation result to cache
   */
  def writeCache(result: TurbineCompileResult): Unit = {
    if (!isCacheEnabled) return

    val timer = new Timer(time)
    try {
      val bytes = result.lowered.bytes()
      if (bytes.isEmpty()) {
        scribe.debug("turbine-cache: skipping write, no classes to cache")
        return
      }

      Files.createDirectories(cachePath.getParent())

      Using.resource(
        new JarOutputStream(
          new BufferedOutputStream(
            Files.newOutputStream(
              cachePath,
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING,
            )
          )
        )
      ) { jos =>
        bytes.forEach { (binaryName, classBytes) =>
          addEntry(jos, binaryName + ".class", classBytes)
        }
      }

      scribe.info(
        s"turbine-cache: wrote ${result.lowered.symbols().size()} classes in ${timer.elapsedMillis}ms"
      )
    } catch {
      case NonFatal(e) =>
        scribe.warn(s"turbine-cache: failed to write cache: ${e.getMessage}")
    }
  }

  /**
   * Reads the cached Turbine compilation result from disk.
   *
   * @return The cached result, or None if cache doesn't exist or is invalid
   */
  def readCache(classpath: Seq[Path]): Option[TurbineCompileResult] = {
    if (!isCacheEnabled) {
      scribe.debug("turbine-cache: caching is disabled")
      None
    } else if (!Files.exists(cachePath)) {
      scribe.debug("turbine-cache: no cache file found")
      None
    } else {
      val timer = new Timer(time)
      try {
        val bytesBuilder = ImmutableMap.builder[String, Array[Byte]]()
        val symbolsBuilder = ImmutableSet.builder[ClassSymbol]()

        Using.resource(new Zip.ZipIterable(cachePath)) { zipIterable =>
          zipIterable.forEach { entry =>
            val name = entry.name()
            if (name.endsWith(".class")) {
              val binaryName = name.stripSuffix(".class")
              val sym = new ClassSymbol(binaryName)
              symbolsBuilder.add(sym)
              bytesBuilder.put(binaryName, entry.data())
            }
          }
        }

        val lowered = Lower.Lowered.create(
          bytesBuilder.build(),
          symbolsBuilder.build(),
        )
        // Bind the project classpath (libraries) so dependency symbols remain
        // discoverable when serving classes from the cached lowered output.
        val classPath = ClassPathBinder.bindClasspath(classpath.asJava)
        val result = TurbineCompileResult(classPath, lowered)

        scribe.info(
          s"turbine-cache: loaded ${lowered.symbols().size()} classes in ${timer.elapsedMillis}ms"
        )
        Some(result)
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
      Files.deleteIfExists(cachePath)
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
