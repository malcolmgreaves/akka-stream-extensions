package com.mfglabs.stream

import java.io.{BufferedInputStream, FileInputStream, InputStream, File}
import java.util.zip.GZIPInputStream

import akka.stream.FlattenStrategy
import akka.stream.scaladsl.Source
import akka.util.ByteString
import akka.actor.ActorRef
import com.mfglabs.stream.internals.source.{UnfoldPullerAsync, BulkPullerAsync}

import scala.concurrent._

trait SourceExt {
  val defaultChunkSize = 8 * 1024

  private[stream] def readFromStream(is: InputStream, maxChunkSize: Int): Option[ByteString] = {
    val buffer = new Array[Byte](maxChunkSize)
    val bytesRead = is.read(buffer)
    bytesRead match {
      case -1 => None
      case `maxChunkSize` => Some(ByteString(buffer))
      case read => Some(ByteString.fromArray(buffer, 0, read))
    }
  }

  /**
   * Create a Source from an InputStream.
   * @param is source input stream
   * @param maxChunkSize max size of the chunks that the source will emit (in bytes).
   * @param ec ec that will be used for the input stream's blocking operations
   * @return
   */
  def fromStream(is: InputStream, maxChunkSize: Int = defaultChunkSize)(implicit ec: ExecutionContextForBlockingOps): Source[ByteString, ActorRef] = {
    bulkPullerAsync[ByteString](0) { (counter, demand) =>
      Future {
        val fulfillments = Vector.fill(demand)(readFromStream(is, maxChunkSize)).flatten
        val stop = (fulfillments.size != demand)
        (fulfillments, stop)
      }(ec.value)
    }
  }

  /**
   * Create a Source from a zipped input stream and unzip it on the fly.
   * @param is source input stream
   * @param maxChunkSize max size of the chunks that the source will emit (in bytes).
   * @param ec ec that will be used for the input stream's blocking operations
   * @return
   */
  def fromGZIPStream(is: InputStream, maxChunkSize: Int = defaultChunkSize)(implicit ec: ExecutionContextForBlockingOps): Source[ByteString, ActorRef] =
    fromStream(new GZIPInputStream(is), maxChunkSize)(ec)

  /**
   * Create a Source from a File.
   * @param f file
   * @param maxChunkSize max size of stream chunks in bytes
   * @param ec ec that will be used for the input stream's blocking operations
   * @return
   */
  def fromFile(f: File, maxChunkSize: Int = defaultChunkSize)(implicit ec: ExecutionContextForBlockingOps): Source[ByteString, ActorRef] =
    fromStream(new FileInputStream(f), maxChunkSize)(ec)

  /**
   * Create a Source from a zip File and unzip it on the fly.
   * @param f file
   * @param maxChunkSize max size of the chunks that the source will emit (in bytes).
   * @param ec
   * @return
   */
  def fromGZIPFile(f: File, maxChunkSize: Int = defaultChunkSize)(implicit ec: ExecutionContextForBlockingOps): Source[ByteString, ActorRef] =
    fromGZIPStream(new FileInputStream(f), maxChunkSize)(ec)

  /**
   * Create a source that calls the f function each time that downstream requests more elements.
   * @param offset initial offset
   * @param f pulling function that takes as first argument (offset + nb of already pushed elements into downstream) and
   *          as second argument the maximum number of elements that can be currently pushed downstream.
   *          Returns a sequence of elements to push, and a stop boolean (true means that this is the end of the stream)
   * @tparam A
   * @return
   */
  def bulkPullerAsync[A](offset: Long)(f: (Long, Int) => Future[(Seq[A], Boolean)]): Source[A, ActorRef] =
    Source[A](BulkPullerAsync.props(offset)(f))

  /**
   * Create a source that calls the f function each time that downstream requests more elements.
   * @param zero
   * @param f pulling unfold function that takes a state B and produce optionally an element to push to downstream. It produces
   *          a new state b if we want the stream to continue or no new state if we want the stream to end.
   * @return
   */
  def unfoldPullerAsync[A, B](zero: => B)(f: B => Future[(Option[A], Option[B])]): Source[A, ActorRef] =
    Source[A](UnfoldPullerAsync.props[A, B](zero)(f))

  /**
   * Create a source from the result of a Future redeemed when the stream is materialized.
   *
   * @param futB the future seed
   * @param f the function producing the source from the seed
   * @tparam A
   * @tparam B
   * @return
   */
  def seededLazyAsync[A, B, M](futB: => Future[B])(f: B => Source[A, M]): Source[A, Unit] =
    singleLazyAsync(futB).map(f).flatten(FlattenStrategy.concat)

  /**
   * Create a source from a Lazy Async value that will be evaluated only when the stream is materialized.
   *
   * @param fut
   * @tparam A
   * @return
   */
  def singleLazyAsync[A](fut: => Future[A]): Source[A, Unit] = singleLazy(fut).mapAsync(identity)

  /**
   * Create a source from a Lazy Value that will be evaluated only when the stream is materialized.
   *
   * @param a
   * @tparam A
   * @return
   */
  def singleLazy[A](a: => A): Source[A, Unit] = Source.single(() => a).map(_())

  /**
   * Create an infinite source of the same Async Lazy value evaluated only when the stream is materialized.
   *
   * @param fut
   * @tparam A
   * @return
   */
  def constantLazyAsync[A](fut: => Future[A]): Source[A, ActorRef] = constantLazy(fut).mapAsync(identity)

  /**
   * Create an infinite source of the same Lazy value evaluated only when the stream is materialized.
   *
   * @param a
   * @tparam A
   * @return
   */
  def constantLazy[A](a: => A): Source[A, ActorRef] = 
    unfoldPullerAsync(a)(evaluatedA => Future.successful(Some(evaluatedA), Some(evaluatedA)))
}

object SourceExt extends SourceExt