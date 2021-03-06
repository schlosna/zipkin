/*
 * Copyright 2012 Twitter Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.twitter.zipkin.collector

import com.twitter.app.App
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.finagle.{Filter, Service}
import com.twitter.util.{Await, Closable, CloseAwaitably, Duration, Future, Time}
import com.twitter.zipkin.common.Span
import com.twitter.zipkin.conversions.thrift._
import com.twitter.zipkin.gen.{Span => ThriftSpan}
import com.twitter.zipkin.storage.{SpanStore, WriteSpanStore}

sealed trait AwaitableCloser extends Closable with CloseAwaitably

object SpanConvertingFilter extends Filter[Seq[ThriftSpan], Unit, Seq[Span], Unit] {
  def apply(spans: Seq[ThriftSpan], svc: Service[Seq[Span], Unit]): Future[Unit] =
    svc(spans.map(_.toSpan))
}

/**
 * A basic collector from which to create a server. Your collector will extend this
 * and implement `newReceiver` and `newSpanStore`. The base collector glues those two
 * together.
 */
trait ZipkinCollectorFactory {
  def newReceiver(receive: Seq[ThriftSpan] => Future[Unit], stats: StatsReceiver): SpanReceiver
  def newSpanStore(stats: StatsReceiver): WriteSpanStore

  // overwrite in the Main trait to add a SpanStore filter to the SpanStore
  def spanStoreFilter: SpanStore.Filter = Filter.identity[Seq[Span], Unit]

  def newCollector(stats: StatsReceiver): AwaitableCloser = new AwaitableCloser {
    val store = newSpanStore(stats)
    val receiver = newReceiver(SpanConvertingFilter andThen spanStoreFilter andThen store, stats)

    def close(deadline: Time): Future[Unit] = closeAwaitably {
      Closable.sequence(receiver, store).close(deadline)
    }
  }
}

/**
 * A base collector that inserts a configurable queue between the receiver and store.
 */
trait ZipkinQueuedCollectorFactory extends ZipkinCollectorFactory { self: App =>
  val itemQueueMax = flag("zipkin.itemQueue.maxSize", 500, "max number of span items to buffer")
  val itemQueueConcurrency = flag("zipkin.itemQueue.concurrency", 10, "number of concurrent workers to process the write queue")

  override def newCollector(stats: StatsReceiver): AwaitableCloser = new AwaitableCloser {
    val store = newSpanStore(stats)

    val queue = new ItemQueue[Seq[ThriftSpan], Unit](
      itemQueueMax(),
      itemQueueConcurrency(),
      SpanConvertingFilter andThen spanStoreFilter andThen store,
      stats.scope("ItemQueue"))

    val receiver = newReceiver(queue.add, stats)

    def close(deadline: Time): Future[Unit] = closeAwaitably {
      Closable.sequence(receiver, queue, store).close(deadline)
    }
  }
}
