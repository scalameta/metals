package scala.meta.languageserver.search
import java.util.concurrent.ConcurrentHashMap
import scala.meta.languageserver.ScalametaLanguageServer.cacheDirectory
import scala.meta.languageserver.compiler.CompilerConfig
import scala.meta.languageserver.{Effects, ServerConfig, ctags}
import scala.meta.languageserver.storage.LevelDBMap
import com.typesafe.scalalogging.Logger
import monix.reactive.Observer
import org.langmeta.internal.semanticdb.schema.{Database, Document}
import org.langmeta.io.AbsolutePath

object IndexDependencyClasspath {
  // NOTE(olafur) this probably belongs somewhere else than Compiler, see
  // https://github.com/scalameta/language-server/issues/48
  def apply(
      serverConfig: ServerConfig,
      indexedJars: ConcurrentHashMap[AbsolutePath, Unit],
      documentSubscriber: Observer.Sync[Document],
      logger: Logger,
      sourceJars: List[AbsolutePath]
  ): Effects.IndexSourcesClasspath = {
    if (!serverConfig.indexClasspath) return Effects.IndexSourcesClasspath
    val sourceJarsWithJDK =
      if (serverConfig.indexJDK)
        CompilerConfig.jdkSources.fold(sourceJars)(_ :: sourceJars)
      else sourceJars
    val buf = List.newBuilder[AbsolutePath]
    sourceJarsWithJDK.foreach { jar =>
      // ensure we only index each jar once even under race conditions.
      // race conditions are not unlikely since multiple .compilerconfig
      // are typically created at the same time for each project/configuration
      // combination. Duplicate tasks are expensive, for example we don't want
      // to index the JDK twice on first startup.
      indexedJars.computeIfAbsent(jar, _ => buf += jar)
    }
    val sourceJarsToIndex = buf.result()
    // Acquire a lock on the leveldb cache only during indexing.
    LevelDBMap.withDB(cacheDirectory.resolve("leveldb").toFile) { db =>
      sourceJarsToIndex.foreach { path =>
        logger.info(s"Indexing classpath entry $path...")
        val database = db.getOrElseUpdate[AbsolutePath, Database](path, { () =>
          ctags.Ctags.indexDatabase(path :: Nil)
        })
        database.documents.foreach(documentSubscriber.onNext)
      }
    }
    Effects.IndexSourcesClasspath
  }
}
