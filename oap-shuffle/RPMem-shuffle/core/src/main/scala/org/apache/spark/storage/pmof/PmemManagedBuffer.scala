package org.apache.spark.storage.pmof

import java.io.InputStream
import java.nio.ByteBuffer
import sun.misc.Cleaner
import io.netty.buffer.Unpooled
import java.util.concurrent.atomic.AtomicInteger
import io.netty.buffer.ByteBuf

import org.apache.spark.internal.Logging
import org.apache.spark.network.buffer.ManagedBuffer

class PmemManagedBuffer(pmHandler: PersistentMemoryHandler, blockId: String) extends ManagedBuffer with Logging {
  var inputStream: InputStream = _
  var total_size: Long = -1
  var buf: ByteBuf = _
  var byteBuffer: ByteBuffer = _
  private val refCount = new AtomicInteger(1)

  override def size(): Long = {
    if (total_size == -1) {
      total_size = pmHandler.getPartitionSize(blockId)
    }
    total_size
  }

  override def nioByteBuffer(): ByteBuffer = {
    // TODO: This function should be Deprecated by spark in near future.
    val data_length = size().toInt
    val in = createInputStream()
    val data = Array.ofDim[Byte](data_length)
    if (buf == null) {
      buf = NettyByteBufferPool.allocateNewBuffer(data_length)
      byteBuffer = buf.nioBuffer(0, data_length)
    } else {
      byteBuffer.clear()
    }
    in.read(data)
    byteBuffer.put(data)
    byteBuffer.flip()
    byteBuffer
  }

  override def createInputStream(): InputStream = {
    if (inputStream == null) {
      inputStream = new PmemInputStream(pmHandler, blockId)
    }
    inputStream
  }

  override def retain(): ManagedBuffer = {
    refCount.incrementAndGet()
    this
  }

  override def release(): ManagedBuffer = {
    if (refCount.decrementAndGet() == 0) {
      if (buf != null) {
        NettyByteBufferPool.releaseBuffer(buf)
      }
      /*if (byteBuffer != null) {
        val cleanerField: java.lang.reflect.Field = byteBuffer.getClass.getDeclaredField("cleaner")
        cleanerField.setAccessible(true)
        val cleaner: Cleaner = cleanerField.get(byteBuffer).asInstanceOf[Cleaner]
        cleaner.clean()
      }*/
      if (inputStream != null) {
        inputStream.close()
      }
    }
    this
  }

  override def convertToNetty(): Object = {
    val in = createInputStream()
    val data_length = size().toInt
    Unpooled.wrappedBuffer(in.asInstanceOf[PmemInputStream].getByteBufferDirectAddr, data_length, false)
  }
}
