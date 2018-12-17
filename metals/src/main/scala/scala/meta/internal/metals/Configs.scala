package scala.meta.internal.metals

import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions
import org.eclipse.lsp4j.FileSystemWatcher
import scala.meta.io.AbsolutePath
import scala.collection.JavaConverters._

object Configs {

  final case class DirectoryGlobConfig(value: String) {
    import DirectoryGlobConfig._
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

  object DirectoryGlobConfig {
    def uri = new DirectoryGlobConfig("uri")
    def vscode = new DirectoryGlobConfig("vscode")
    def default = new DirectoryGlobConfig(
      System.getProperty("metals.directory-glob", uri.value)
    )
  }

}
