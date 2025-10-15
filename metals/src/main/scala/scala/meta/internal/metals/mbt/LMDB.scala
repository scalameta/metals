package scala.meta.internal.metals.mbt

import java.io.Closeable
import java.lang.reflect.InaccessibleObjectException
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.Collections
import java.util.HashMap

import scala.util.Failure
import scala.util.Success
import scala.util.Using
import scala.util.control.NonFatal

import scala.meta.io.AbsolutePath

import org.lmdbjava.Dbi
import org.lmdbjava.DbiFlags
import org.lmdbjava.Env
import org.lmdbjava.LmdbNativeException.ConstantDerivedException
import org.lmdbjava.Txn

object LMDB {

  /**
   * Returns true if this JVM process has been configured with the correct VM
   * options for LMDB to function correctly.
   *
   * If not, we log a warning with instructions on how to fix the problem.
   */
  def isSupportedOrWarn(): Boolean = {
    try {
      val address = classOf[Buffer].getDeclaredField("address")
      address.setAccessible(true)
      true
    } catch {
      case _: InaccessibleObjectException =>
        scribe.warn(
          s"""|Invalid config, can't use `"workspaceSymbolProvider": "mbt"` without additional JVM options.
              |To fix this problem, add the following settings:
              |
              |  "metals.serverProperties": [
              |    // Existing server properties
              |    "--add-opens=java.base/java.nio=ALL-UNNAMED",
              |    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
              |  ]
              |
              |Restart Metals ("Reload Window") to apply the new JVM options.
              |For this server instance, falling back to "bsp" workspace symbol provider.
              |""".stripMargin
        )
        false

    }
  }

}

// IMPORTANTLY: the LMDB should only be used as a cache, we will wipe the DB if
// it gets corrupted for whatever reason. Use the H2 database to store data that
// you don't want to lose. Also, LMDB uses minimum 4kb disk *per entry* so it's
// very inefficient for small data where the H2 database is great.
class LMDB(val workspace: AbsolutePath) extends Closeable {
  private val dbpath = workspace.resolve(".metals").resolve("mbt")
  @volatile private var initialized = false
  lazy val env: Env[ByteBuffer] = {
    initialized = true
    loadEnv()
  }
  private val dbs =
    Collections.synchronizedMap(new HashMap[String, Dbi[ByteBuffer]])
  private def openDB(name: String): Dbi[ByteBuffer] =
    dbs.computeIfAbsent(name, env.openDbi(_, DbiFlags.MDB_CREATE))

  def close(): Unit = {
    if (!initialized) {
      return
    }
    if (!LMDB.isSupportedOrWarn()) {
      return
    }
    try {
      dbs.values().forEach(db => db.close())
      dbs.clear()
      env.close()
    } catch {
      case NonFatal(e) =>
        scribe.error("Error closing LMDB", e)
    }
  }

  private def loadEnv(): Env[ByteBuffer] = {
    Files.createDirectories(dbpath.toNIO)
    Env
      .create()
      // DB: error if the database grows beyond 8GB, which is ~5x more than what we need right now.
      .setMapSize((8_000L * 1024L * 1024L))
      .open(dbpath.toFile)
  }

  def readTransaction[T](
      name: String,
      doingWhat: String,
      default: T = null,
  )(
      fn: (
          Env[ByteBuffer],
          Dbi[ByteBuffer],
          Txn[ByteBuffer],
          Using.Manager,
      ) => T
  ): T = withRecovery(name, doingWhat, default) { use =>
    val db = openDB(name)
    val txn = use(env.txnRead())
    fn(env, db, txn, use)
  }

  def writeTransaction[T](
      name: String,
      doingWhat: String,
      default: T = null,
  )(
      fn: (
          Env[ByteBuffer],
          Dbi[ByteBuffer],
          Txn[ByteBuffer],
          Using.Manager,
      ) => T
  ): T = withRecovery(name, doingWhat, default) { use =>
    val db = openDB(name)
    val txn = use(env.txnWrite())
    try fn(env, db, txn, use)
    catch {
      case NonFatal(e) =>
        scribe.error(
          s"${doingWhat} - unexpected error while writing to LMDB table '${name}'",
          e,
        )
        txn.abort()
        throw e
    }
  }

  private def withRecovery[T](
      name: String,
      doingWhat: String,
      default: T,
      depth: Int = 0,
  )(fn: Using.Manager => T): T = {
    if (depth > 1) {
      scribe.error(
        s"${doingWhat} - LMDB table '${name}' is corrupted even after dropping."
      )
      return default
    }
    Using.Manager(fn) match {
      case Failure(_: ConstantDerivedException) =>
        scribe.error(
          s"${doingWhat} - LMDB table '${name}' is corrupted. Cleaning the cache."
        )
        val db = openDB(name)
        val txn = env.txnWrite()
        try {
          db.drop(txn)
          txn.commit()
        } finally {
          txn.close()
        }
        withRecovery(name, doingWhat, default, depth + 1)(fn)
      case Failure(e) =>
        scribe.error(
          s"${doingWhat} - unexpected error while writing to LMDB table '${name}'",
          e,
        )
        default
      case Success(value) =>
        value
    }
  }
}
