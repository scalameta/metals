package scala.meta.internal.metals.mbt

import java.nio.ByteBuffer
import java.nio.file.Files

import scala.util.Using
import scala.util.control.NonFatal

import scala.meta.io.AbsolutePath

import org.lmdbjava.Dbi
import org.lmdbjava.DbiFlags
import org.lmdbjava.Env
import org.lmdbjava.Txn

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
