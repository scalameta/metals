package scala.meta.internal.metals.mbt.importer.bazel

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest

import scala.util.Try

/** Download missing lockfile artifacts without exposing incomplete cache files. */
private[bazel] object MavenArtifactDownload {
  def download(
      url: String,
      relative: String,
      checksum: Option[String],
  ): Option[String] = {
    Try {
      val uri = URI.create(url)
      require(
        Set("http", "https").contains(uri.getScheme),
        "Unsupported repository URL",
      )
      val cache = Path.of(System.getProperty("user.home"), ".m2", "repository")
      val target = cache.resolve(relative).normalize()
      require(target.startsWith(cache), "Artifact path escapes Maven cache")
      Files.createDirectories(target.getParent)
      val temporary =
        Files.createTempFile(target.getParent, ".metals-download-", ".tmp")
      try {
        val connection = uri.toURL.openConnection()
        connection.setConnectTimeout(10000)
        connection.setReadTimeout(30000)
        val digest = MessageDigest.getInstance("SHA-256")
        val in = new DigestInputStream(connection.getInputStream, digest)
        try Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING)
        finally in.close()
        val actual = digest.digest().map(b => f"${b & 0xff}%02x").mkString
        require(checksum.forall(_.equalsIgnoreCase(actual)), "SHA-256 mismatch")
        Files.move(
          temporary,
          target,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING,
        )
        target.toUri.toString
      } finally Files.deleteIfExists(temporary)
    }.fold(
      error => {
        scribe.warn(s"bazel-mbt: could not download $url: ${error.getMessage}")
        None
      },
      Some(_),
    )
  }
}
