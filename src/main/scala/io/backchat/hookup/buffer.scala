package io.backchat.hookup

import java.io._
import java.util.concurrent.ConcurrentLinkedQueue
import collection.mutable
import scala.concurrent.{ Promise, ExecutionContext, Future }
import net.liftweb.json.Formats
import collection.JavaConverters._
import java.util.Queue
import collection.mutable.{ Queue ⇒ ScalaQueue }

/**
 * Companion object for the [[io.backchat.hookup.FileBuffer]]
 */
object FileBuffer {
  private object State extends Enumeration {
    val Closed, Draining, Open = Value
  }
}

/**
 * Interface trait to which fallback mechanisms must adhere to
 * It implements [[java.io.Closeable]] so you can use it as a resource
 */
trait BackupBuffer extends Closeable {

  /**
   * open the buffer
   */
  def open()

  /**
   * close the buffer, closing all external resources used by this buffer
   */
  def close()

  /**
   * Write a line to the buffer
   * @param line A [[io.backchat.hookup.OutboundMessage]]
   */
  def write(line: OutboundMessage)(implicit wireFormat: WireFormat)
  def drain(readLine: (OutboundMessage ⇒ Future[OperationResult]))(implicit executionContext: ExecutionContext, wireFormat: WireFormat): Future[OperationResult]
}

class MemoryBuffer(memoryBuffer: Queue[String] = new ConcurrentLinkedQueue[String]()) extends BackupBuffer {

  /**
   * open the buffer
   */
  def open() {}

  /**
   * close the buffer, closing all external resources used by this buffer
   */
  def close() {}

  def write(line: OutboundMessage)(implicit wireFormat: WireFormat) {
    memoryBuffer.offer(wireFormat.render(line))
  }

  def drain(readLine: (OutboundMessage) => Future[OperationResult])(implicit executionContext: ExecutionContext, wireFormat: WireFormat) = {
    var futures = mutable.ListBuffer[Future[OperationResult]]()
    while (!memoryBuffer.isEmpty) { // and then the memory buffer
      val msg = memoryBuffer.poll()
      if (msg.nonBlank)
        futures += readLine(wireFormat.parseOutMessage(msg))
    }
    if (futures.isEmpty) Promise.successful(Success).future else Future.sequence(futures.toList).map(ResultList(_))
    //if (futures.isEmpty) Promise.successful(Success) else Future.sequence(futures.toList).map(ResultList(_))
  }
}
//
//abstract class BufferFactory(val id: String) {
//  def create(wireFormat: WireFormat): BackupBuffer
//}
//
//class FileBufferFactory private[hookup] (file: File, writeToFile: Boolean, memoryBuffer: Queue[String]) extends BufferFactory("file_buffer") {
//  def this(file: File) = this(file, true, new ConcurrentLinkedQueue[String]())
//  def create(wireFormat: WireFormat) = new FileBuffer(file, writeToFile, memoryBuffer)(wireFormat)
//}

/**
 * The default file buffer.
 * This is a file buffer that also has a memory buffer to which it writes when the file stream is
 * being read out or if a write to the file failed.
 *
 * This class has no maximum size or any limits attached to it yet.
 * So it is possible for this class to exhaust the memory and/or disk space of the machine using this buffer.
 *
 * @param file
 */
class FileBuffer private[hookup] (file: File, writeToFile: Boolean, memoryBuffer: Queue[String]) extends BackupBuffer {

  def this(file: File) = this(file, true, new ConcurrentLinkedQueue[String]())

  import FileBuffer._

  @volatile private[this] var output: PrintWriter = _
  @volatile private[this] var state = State.Closed

  /**
   * Open this file buffer if not already opened.
   * This method is idempotent.
   */
  def open() { if (state == State.Closed) openFile(true) }

  @inline private[this] def openFile(append: Boolean) {
    val dir = file.getAbsoluteFile.getParentFile
    if (!dir.exists()) dir.mkdirs()
    output = new PrintWriter(new BufferedOutputStream(new FileOutputStream(file, append)), true)
    state = if (writeToFile) State.Open else State.Draining
  }

  /**
   * Write a message to the buffer.
   * When the buffer is opened it will write a new line to the file
   * When the buffer is closed it will open the buffer and then write the new line.
   * When the buffer is being drained it will buffer to memory
   * When an exception is thrown it will first buffer the message to memory and then rethrow the exception
   * @param message A [[io.backchat.hookup.OutboundMessage]]
   */
  def write(message: OutboundMessage)(implicit wireFormat: WireFormat): Unit = synchronized {
    val msg = wireFormat.render(message)
    try {
      state match {
        case State.Open ⇒ {
          output.println(msg)
        }
        case State.Closed ⇒ openFile(true); output.println(msg)
        case State.Draining ⇒
          memoryBuffer.offer(msg)
      }
    } catch {
      case e =>
        memoryBuffer.offer(msg)
        throw e
    }

  }

  private[this] def serializeAndSave(message: OutboundMessage)(save: String ⇒ Unit)(implicit wireFormat: WireFormat) = {
    save(wireFormat.render(message))
  }

  /**
   * Drain the buffer using the `readLine` function to process each message in the buffer.
   * This method works with [[scala.concurrent.Future]] objects and needs an [[akka.dispatch.ExecutionContext]] in scope
   *
   * @param readLine A function that takes a [[io.backchat.hookup.OutboundMessage]] and produces a [[scala.concurrent.Future]] of [[io.backchat.hookup.OperationResult]]
   * @param executionContext An [[akka.dispatch.ExecutionContext]]
   * @return A [[scala.concurrent.Future]] of [[io.backchat.hookup.OperationResult]]
   */
  def drain(readLine: (OutboundMessage ⇒ Future[OperationResult]))(implicit executionContext: ExecutionContext, wireFormat: WireFormat): Future[OperationResult] = synchronized {
    var futures = mutable.ListBuffer[Future[OperationResult]]()
    state = State.Draining
    close()
    var input: BufferedReader = null
    var append = true
    try {
      if (file != null) {
        input = new BufferedReader(new FileReader(file))
        var line = input.readLine()
        while (line != null) { // first drain the file buffer
          if (line.nonBlank) {
            futures += readLine(wireFormat.parseOutMessage(line))
          }
          line = input.readLine()
        }
      }
      while (!memoryBuffer.isEmpty) { // and then the memory buffer
        val msg = memoryBuffer.poll()
        if (msg.nonBlank)
          futures += readLine(wireFormat.parseOutMessage(msg))
      }
      val res = if (futures.isEmpty) Promise.successful(Success).future else Future.sequence(futures.toList).map(ResultList(_))
      append = false
      res
    } catch {
      case e ⇒
        e.printStackTrace()
        Promise.failed(e).future
    } finally {
      if (input != null) {
        input.close()
      }
      openFile(append)
    }
  }

  /**
   * Closes the buffer and releases any external resources contained by this buffer.
   * This method is idempotent.
   */
  def close() {
    if (state != State.Closed) {
      if (output != null) output.close()
      output = null
      state = State.Closed
    }
  }

}
