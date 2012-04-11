package io.backchat.websocket

import java.io._
import java.util.concurrent.ConcurrentLinkedQueue
import io.backchat.websocket.WebSocket.ParseToWebSocketOutMessage
import collection.mutable
import org.jboss.netty.logging.InternalLogger
import akka.dispatch.{ Promise, ExecutionContext, Future }
import net.liftweb.json.Formats
import collection.JavaConverters._
import java.util.Queue
import collection.mutable.{ Queue ⇒ ScalaQueue }

object FileBuffer {
  private object State extends Enumeration {
    val Closed, Draining, Open = Value
  }
}

trait BackupBuffer {
  def open()
  def close()
  def write(line: WebSocketOutMessage)
  def drain(readLine: (WebSocketOutMessage ⇒ Future[OperationResult]))(implicit executionContext: ExecutionContext): Future[OperationResult]
}

class FileBuffer private[websocket] (file: File, writeToFile: Boolean, memoryBuffer: Queue[String])(implicit wireFormat: WireFormat) extends BackupBuffer with Closeable {

  def this(file: File)(implicit wireFormat: WireFormat) = this(file, true, new ConcurrentLinkedQueue[String]())

  //TODO: Get rid of all the synchronized and volatile stuff
  import FileBuffer._

  @volatile private[this] var output: PrintWriter = _
  @volatile private[this] var state = State.Closed

  def open() { if (state == State.Closed) openFile(true) }

  @inline private[this] def openFile(append: Boolean) {
    val dir = file.getAbsoluteFile.getParentFile
    if (!dir.exists()) dir.mkdirs()
    output = new PrintWriter(new BufferedOutputStream(new FileOutputStream(file, append)), true)
    state = if (writeToFile) State.Open else State.Draining
  }

  def write(message: WebSocketOutMessage): Unit = synchronized {
    val msg = wireFormat.render(message)
    state match {
      case State.Open ⇒ {
        output.println(msg)
      }
      case State.Closed ⇒ openFile(true); output.println(msg)
      case State.Draining ⇒
        memoryBuffer.offer(msg)
        memoryBuffer.asScala.toSeq
    }
  }

  private[this] def serializeAndSave(message: WebSocketOutMessage)(save: String ⇒ Unit) = {
    save(wireFormat.render(message))
  }

  def drain(readLine: (WebSocketOutMessage ⇒ Future[OperationResult]))(implicit executionContext: ExecutionContext): Future[OperationResult] = synchronized {
    var futures = mutable.ListBuffer[Future[OperationResult]]()
    state = State.Draining
    close()
    var input: BufferedReader = null
    var append = true
    try {
      if (file != null) {
        input = new BufferedReader(new FileReader(file))
        var line = input.readLine()
        while (line != null) {
          if (line.nonBlank) {
            readLine(wireFormat.parseOutMessage(line))
          }
          line = input.readLine()
        }
        while (!memoryBuffer.isEmpty) {
          val line = memoryBuffer.poll()
          if (line.nonBlank) readLine(wireFormat.parseOutMessage(line))
        }
        val res = Future.sequence(futures.toList).map(ResultList(_))
        append = false
        res
      } else Promise.successful(Success)
    } catch {
      case e ⇒
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
      if (output != null) output.close()
      output = null
      state = State.Closed
    }
  }
}
