package langserver.core

import com.typesafe.scalalogging.LazyLogging

object Main extends LazyLogging {
  def main(args: Array[String]): Unit = {
    logger.info(s"Starting server in ${System.getenv("PWD")}")

    val server = new LanguageServer(System.in, System.out)
    server.start()
  }
}
