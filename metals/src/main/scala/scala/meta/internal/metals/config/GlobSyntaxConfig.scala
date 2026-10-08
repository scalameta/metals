package scala.meta.internal.metals.config

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions
import org.eclipse.lsp4j.FileSystemWatcher
import org.eclipse.lsp4j.jsonrpc.messages.{Either => JEither}

final case class GlobSyntaxConfig(value: String) {
  import GlobSyntaxConfig._
  def isUri: Boolean = this == uri
  def isPath: Boolean = this == path
  def registrationOptions(
      workspace: AbsolutePath
  ): DidChangeWatchedFilesRegistrationOptions = {
    val root: String =
      if (isUri) workspace.toURI.toString.stripSuffix("/")
      else workspace.toString()
    new DidChangeWatchedFilesRegistrationOptions(
      (List(
        new FileSystemWatcher(JEither.forLeft(s"$root/**/*.scala")),
        new FileSystemWatcher(JEither.forLeft(s"$root/**/*.java")),
        new FileSystemWatcher(JEither.forLeft(s"$root/**/*.proto")),
        new FileSystemWatcher(JEither.forLeft(s"$root/*.sbt")),
        new FileSystemWatcher(JEither.forLeft(s"$root/**/pom.xml")),
        new FileSystemWatcher(JEither.forLeft(s"$root/*.sc")),
        new FileSystemWatcher(JEither.forLeft(s"$root/**/*?.gradle")),
        new FileSystemWatcher(JEither.forLeft(s"$root/**/*.gradle.kts")),
        new FileSystemWatcher(
          JEither.forLeft(s"$root/project/*.{scala,sbt}")
        ),
        new FileSystemWatcher(
          JEither.forLeft(s"$root/project/project/*.{scala,sbt}")
        ),
        new FileSystemWatcher(
          JEither.forLeft(s"$root/project/build.properties")
        ),
        new FileSystemWatcher(
          JEither.forLeft(s"$root/.metals/.reports/bloop/*/*")
        ),
        new FileSystemWatcher(
          JEither.forLeft(s"$root/.metals/mbt.json")
        ),
        new FileSystemWatcher(JEither.forLeft(s"$root/**/.bsp/*.json")),
      ) ++ bazelPaths(root)).asJava
    )
  }

  def bazelPaths(root: String): List[FileSystemWatcher] =
    List(
      new FileSystemWatcher(JEither.forLeft(s"$root/**/BUILD")),
      new FileSystemWatcher(JEither.forLeft(s"$root/**/BUILD.bazel")),
      new FileSystemWatcher(JEither.forLeft(s"$root/WORKSPACE")),
      new FileSystemWatcher(JEither.forLeft(s"$root/WORKSPACE.bazel")),
      new FileSystemWatcher(JEither.forLeft(s"$root/**/*.bzl")),
      new FileSystemWatcher(JEither.forLeft(s"$root/*.bazelproject")),
    )
}

object GlobSyntaxConfig {
  def uri = new GlobSyntaxConfig("uri")
  def path = new GlobSyntaxConfig("vscode")
  def default =
    new GlobSyntaxConfig(
      // Default to plain path globs (LSP 3.17); URI-prefixed patterns break strict
      // clients (e.g. Neovim 0.12). Override with -Dmetals.glob-syntax=uri if needed.
      System.getProperty("metals.glob-syntax", path.value)
    )
  def fromString(value: String): Option[GlobSyntaxConfig] =
    value match {
      case "vscode" => Some(path)
      case "path" => Some(path)
      case "uri" => Some(uri)
      case _ => None
    }
}
