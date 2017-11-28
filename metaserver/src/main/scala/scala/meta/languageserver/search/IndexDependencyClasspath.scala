package scala.meta.languageserver.search
import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable
import scala.meta.languageserver.ScalametaLanguageServer.cacheDirectory
import scala.meta.languageserver.ctags
import scala.meta.languageserver.storage.LevelDBMap
import com.typesafe.scalalogging.LazyLogging
import org.langmeta.internal.semanticdb.schema.{Database, Document}
import org.langmeta.io.AbsolutePath

object IndexDependencyClasspath extends LazyLogging {
  def apply(
      indexedJars: ConcurrentHashMap[AbsolutePath, Unit],
      documentAction: Document => Unit,
      sourceJars: List[AbsolutePath],
      buf: mutable.Builder[AbsolutePath, List[AbsolutePath]]
  ): Unit = {
    sourceJars.foreach { jar =>
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
        database.documents.foreach(documentAction)
      }
    }
  }
}
