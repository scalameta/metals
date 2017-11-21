package scala.meta.languageserver.storage

import java.io.File
import com.typesafe.scalalogging.LazyLogging
import org.fusesource.leveldbjni.JniDBFactory
import org.iq80.leveldb.DB
import org.iq80.leveldb.DBException
import org.iq80.leveldb.Options

/**
 * A Scala-friendly wrapper around the JniDBFactory Java-wrapper around leveldb.
 *
 * @param db The leveldb, remember to close it after using. This wrapper will NOT
 *           close the db for you.
 * @param keys type-class to read/write key values from arrays of bytes.
 * @param values type-class to read/write key values from arrays of bytes.
 */
class LevelDBMap[Key, Value](db: DB, keys: Bytes[Key], values: Bytes[Value])
    extends LazyLogging {

  /** Returns new wrapper where the keys are parsed into T. */
  def mapValues[T](f: Value => T, g: T => Value): LevelDBMap[Key, T] =
    new LevelDBMap(db, keys, values.map(f, g))

  /** Returns new wrapper where the values are parsed into T. */
  def mapKeys[T](f: Key => T, g: T => Key): LevelDBMap[T, Value] =
    new LevelDBMap(db, keys.map(f, g), values)

  /** Returns the value matching key, if any. */
  def get(key: Key): Option[Value] = {
    try {
      Option(db.get(keys.toBytes(key))).map(values.fromBytes)
    } catch {
      case e: DBException =>
        logger.error(e.getMessage, e)
        None
    }
  }

  /**
   * Gets the value if it exists, otherwise computes the fallback value and stores it.
   *
   * This method is not thread-safe, the computed fallback value may get overwritten.
   */
  def getOrElseUpdate(key: Key, orElse: () => Value): Value = {
    get(key) match {
      case Some(value) => value
      case None =>
        val computed = orElse()
        put(key, computed)
    }
  }

  /** Inserts a new value for the given key. */
  def put(key: Key, value: Value): Value = {
    try {
      db.put(keys.toBytes(key), values.toBytes(value))
      value
    } catch {
      case e: DBException =>
        logger.error(e.getMessage, e)
        value
    }
  }

  def close(): Unit = db.close()
}

object LevelDBMap {

  /**
   * Construct new wrapper around a leveldb.
   *
   * @tparam T Good values are either String or Array[Byte]. Use mapValues/mapKeys
   *           to use higher level types than String/Array[Byte] for values or keys.
   */
  def apply[T](db: DB)(implicit ev: Bytes[T]): LevelDBMap[T, T] =
    new LevelDBMap(db, ev, ev)

  def apply[Key, Value](db: DB)(
      implicit keys: Bytes[Key],
      values: Bytes[Value]
  ): LevelDBMap[Key, Value] =
    new LevelDBMap(db, keys, values)

  /**
   * Creates a new leveldb in the given directory.
   *
   * Make sure to `db.close()`.
   */
  def createDBThatIPromiseToClose(directory: File): DB = {
    val options = new Options
    options.createIfMissing(true)
    JniDBFactory.factory.open(directory, options)
  }

}
