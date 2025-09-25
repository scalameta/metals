package scala.meta.internal.metals.mbt

import java.lang.reflect.InaccessibleObjectException
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.file.Files

import scala.util.Using
import scala.util.control.NonFatal

import scala.meta.io.AbsolutePath

import org.lmdbjava.Dbi
import org.lmdbjava.DbiFlags
import org.lmdbjava.Env
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

class LMDB(val workspace: AbsolutePath) {
  private val dbpath = workspace.resolve(".metals").resolve("mbt")

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
  ): T =
    Using
      .Manager { use =>
        val env = use(loadEnv())
        val db = env.openDbi(name, DbiFlags.MDB_CREATE)
        val txn = use(env.txnRead())
        fn(env, db, txn, use)
      }
      .recover { case e =>
        scribe.error(
          s"${doingWhat} - unexpected error while reading from LMDB table '${name}'",
          e,
        )
        default
      }
      .get

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
  ): T =
    Using
      .Manager { use =>
        val env = use(loadEnv())
        val db = env.openDbi(name, DbiFlags.MDB_CREATE)
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
      .recover { case e =>
        scribe.error(
          s"${doingWhat} - unexpected error while writing to LMDB table '${name}'",
          e,
        )
        default
      }
      .get
}
