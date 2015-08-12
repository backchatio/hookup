package io.backchat.hookup
package tests

import org.specs2.specification.AfterAll
import org.specs2.time.NoTimeConversions
import org.specs2.Specification
import java.io.File
import org.apache.commons.io.{FilenameUtils, FileUtils}
import org.json4s._
import scala.io.Source
import collection.JavaConverters._
import org.specs2.specification.core.{Fragments}
import java.util.concurrent.{Executors, ConcurrentLinkedQueue}
import scala.concurrent.{Await, Future, ExecutionContext}
import scala.concurrent.duration._
import akka.actor.ActorSystem
import collection.mutable.{ArrayBuffer, Buffer, SynchronizedBuffer, ListBuffer}
import java.util.concurrent.atomic.AtomicInteger

class FileBufferSpec extends Specification with AfterAll { def is =
  "A FileBuffer should" ^
    "create the path to the file if it doesn't exist" ! createsPath ^
    "write to a file while the buffer is open" ! writesToFile ^
    "write to memory buffer while draining" ! writesToMemory ^
    "drain the buffers" ! drainsBuffers ^
    "not fail under concurrent load" ! handlesConcurrentLoads ^
  end

  implicit val wireFormat: WireFormat = new JsonProtocolWireFormat()(DefaultFormats)
  implicit val executionContext = ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())


  override def afterAll(): Unit = executionContext.shutdown()

  def createsPath = {
    val logPath = new File("./test-work/testing/and/such/buffer.log")
    val workPath = new File("./test-work")
    if (workPath.exists()) FileUtils.deleteDirectory(workPath)
    val buff = new FileBuffer(logPath)
    buff.open()
    val res = logPath.getParentFile.exists must beTrue
    FileUtils.deleteDirectory(workPath)
    buff.close()
    res
  }

  def writesToFile = {
    FileUtils.deleteQuietly(new File("./test-work2"))
    val logPath = new File("./test-work2/buffer.log")
    val buff = new FileBuffer(logPath)
    val exp1: OutboundMessage = TextMessage("the first message")
    val exp2: OutboundMessage = TextMessage("the second message")
    buff.open()
    buff.write(exp1)
    buff.write(exp2)
    buff.close()
    val lines = Source.fromFile(logPath).getLines().toList map wireFormat.parseOutMessage
    FileUtils.deleteQuietly(new File("./test-work2"))
    lines must contain(eachOf(exp1, exp2))
  }

  def writesToMemory = {
    val logPath = new File("./test-work3/buffer.log")
    val exp1: OutboundMessage = TextMessage("the first message")
    val exp2: OutboundMessage = TextMessage("the second message")
    val queue = new ConcurrentLinkedQueue[String]()
    val buff = new FileBuffer(logPath, false, queue)
    buff.open()
    buff.write(exp1)
    buff.write(exp2)
    val lst = queue.asScala.toList
    buff.close()
    FileUtils.deleteDirectory(new File("./test-work3"))
    lst must contain(eachOf(wireFormat.render(exp1), wireFormat.render(exp2)))
  }

  def drainsBuffers = {
    val logPath = new File("./test-work4/buffer.log")
    val buff = new FileBuffer(logPath)
    val exp1: OutboundMessage = TextMessage("the first message")
    val exp2: OutboundMessage = TextMessage("the second message")
    buff.open()
    buff.write(exp1)
    buff.write(exp2)
    val lines = new ListBuffer[OutboundMessage]
    Await.ready(buff drain { out =>
      Future {
        lines += out
        Success
      }
    }, 5 seconds)
    buff.close()
    FileUtils.deleteQuietly(new File("./test-work4"))
    lines must contain(eachOf(exp1, exp2))
  }

  def handlesConcurrentLoads = {
    val system = ActorSystem("filebufferconc")
    val logPath = new File("./test-work5/buffer.log")
    val buff = new FileBuffer(logPath)
    val lines = new ArrayBuffer[OutboundMessage] with SynchronizedBuffer[OutboundMessage]
    buff.open()
    val reader = system.scheduler.schedule(50 millis, 50 millis) {
      Await.ready(buff drain { out =>
        Future {
          lines += out
          Success
        }
      }, 5 seconds)
    }
    (1 to 20000) foreach { s =>
      buff.write(TextMessage("message %s" format s))
    }
    reader.cancel()
    Await.ready(buff drain { out =>
          Future {
            lines += out
            Success
          }
        }, 5 seconds)
    buff.close()
    FileUtils.deleteDirectory(new File("./test-work5"))
    system.shutdown()
    lines must haveSize(20000)
  }

}
