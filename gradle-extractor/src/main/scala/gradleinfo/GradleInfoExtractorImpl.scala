package gradleinfo

import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

import scala.util.control.NonFatal

import scala.meta.mbt.MbtExtractor

/**
 * Implementation of the GradleInfoExtractor interface that can be loaded via ServiceLoader.
 * This class delegates to the GradleInfoExtractor object for the actual extraction logic.
 */
class GradleInfoExtractorImpl extends MbtExtractor {

  private val logger = Logger.getLogger(getClass.getName)
  override def extract(
      projectDir: Path,
      outputFile: Path,
  ): Unit = try {
    val config = ExtractorConfig(
      projectDir = projectDir
    )
    val report = GradleInfoExtractor.extract(config)
    val json = report.toMbtJson
    Files.writeString(outputFile, json)
  } catch {
    case NonFatal(e) =>
      logger.log(Level.SEVERE, "Error extracting Gradle info", e)
      throw e
  }
}
