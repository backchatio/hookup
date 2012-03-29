package io.backchat.websocket

import java.io._
import java.util.concurrent.ConcurrentLinkedQueue
import io.backchat.websocket.WebSocket.ParseToWebSocketOutMessage
import collection.mutable
import org.jboss.netty.logging.InternalLogger
import akka.dispatch.{Promise, ExecutionContext, Future}
import net.liftweb.json.Formats
import collection.JavaConverters._

object FileBuffer {
  private object State extends Enumeration {
    val Closed, Draining, Open = Value
  }
}

trait BackupBuffer {
  def open()
  def close()
  def write(line: String)
  def drain(readLine: String => Unit)
}

class FileBuffer(file: File, logger: InternalLogger)(implicit format: Formats) extends Closeable {

  import FileBuffer._
  import net.liftweb.json._
  import JsonDSL._

  @volatile private[this] var output: PrintWriter = _
  private[this] val memoryBuffer = new ConcurrentLinkedQueue[String]()
  @volatile private[this] var state = State.Closed

  def open() = if (state == State.Closed) openFile(true)

  @inline private[this] def openFile(append: Boolean) {
    output = new PrintWriter(new BufferedOutputStream(new FileOutputStream(file, append)), true)
    state = State.Open
  }

  def write(message: WebSocketOutMessage): Unit = {
    val msg = WebSocket.RenderOutMessage(message)
    state match {
      case State.Open => {
        output.println(msg)
      }
      case State.Closed => openFile(true); output.println(msg)
      case State.Draining =>
        memoryBuffer.offer(msg)
        memoryBuffer.asScala.toSeq
    }
  }

  private[this] def serializeAndSave(message: WebSocketOutMessage)(save: String => Unit) = {
    save(WebSocket.RenderOutMessage(message))
  }

  def drain(readLine: (WebSocketOutMessage => Future[OperationResult]))(implicit executionContext: ExecutionContext): Future[OperationResult] = synchronized {
    var futures = mutable.ListBuffer[Future[OperationResult]]()
    close()
    state = State.Draining
    var input: BufferedReader = null
    var append = true
    try {
      input = new BufferedReader(new FileReader(file))
      var line = input.readLine()
      while(line != null) {
        if (line.nonBlank) {
          readLine(ParseToWebSocketOutMessage(line))
        }
        line = input.readLine()
      }
      while(!memoryBuffer.isEmpty) {
        val line = memoryBuffer.poll()
        if (line.nonBlank) readLine(ParseToWebSocketOutMessage(line))
      }
      val res = Future.sequence(futures.toList).map(ResultList(_))
      append = false
      res
    } catch {
      case e =>
        e.printStackTrace()
        Promise.failed(e)
    } finally {
      if (input != null) {
        input.close()
      }
      openFile(append)
    }
  }

  def close() {
    if (state != State.Closed) {
      output.close()
      output = null
      state = State.Closed
    }
  }
}
