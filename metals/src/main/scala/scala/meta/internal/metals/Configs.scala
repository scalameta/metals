package scala.meta.internal.metals

import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions
import org.eclipse.lsp4j.FileSystemWatcher
import scala.meta.io.AbsolutePath
import scala.collection.JavaConverters._

object Configs {

  final case class GlobSyntaxConfig(value: String) {
    import GlobSyntaxConfig._
    def isUri: Boolean = this == uri
    def isVscode: Boolean = this == vscode
    def registrationOptions(
        workspace: AbsolutePath
    ): DidChangeWatchedFilesRegistrationOptions = {
      val root: String =
        if (isVscode) workspace.toString()
        else workspace.toURI.toString.stripSuffix("/")
      new DidChangeWatchedFilesRegistrationOptions(
        List(
          new FileSystemWatcher(s"$root/*.sbt"),
          new FileSystemWatcher(s"$root/project/*.{scala,sbt}"),
          new FileSystemWatcher(s"$root/project/build.properties")
        ).asJava
      )
    }
  }

  object GlobSyntaxConfig {
    def uri = new GlobSyntaxConfig("uri")
    def vscode = new GlobSyntaxConfig("vscode")
    def default = new GlobSyntaxConfig(
      System.getProperty("metals.glob-syntax", uri.value)
    )
  }

  final case class SyntaxErrors(value: String) {
    def isOnType: Boolean = value == "on-type"
    def isCompile: Boolean = value == "on-compile"
  }

}
