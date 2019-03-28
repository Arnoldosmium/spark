/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.shuffle.io

import java.io.InputStream

import scala.collection.JavaConverters._

import org.apache.spark.{MapOutputTracker, SparkConf, TaskContext}
import org.apache.spark.api.shuffle.{ShuffleBlockInfo, ShuffleReadSupport}
import org.apache.spark.internal.config
import org.apache.spark.serializer.SerializerManager
import org.apache.spark.shuffle.ShuffleReadMetricsReporter
import org.apache.spark.storage.{BlockId, BlockManager, ShuffleBlockFetcherIterator}

class DefaultShuffleReadSupport(
    blockManager: BlockManager,
    serializerManager: SerializerManager,
    mapOutputTracker: MapOutputTracker,
    conf: SparkConf) extends ShuffleReadSupport {

  private val maxBytesInFlight = conf.get(config.REDUCER_MAX_SIZE_IN_FLIGHT) * 1024 * 1024
  private val maxReqsInFlight = conf.get(config.REDUCER_MAX_REQS_IN_FLIGHT)
  private val maxBlocksInFlightPerAddress =
    conf.get(config.REDUCER_MAX_BLOCKS_IN_FLIGHT_PER_ADDRESS)
  private val maxReqSizeShuffleToMem = conf.get(config.MAX_REMOTE_BLOCK_SIZE_FETCH_TO_MEM)
  private val detectCorrupt = conf.get(config.SHUFFLE_DETECT_CORRUPT)

  override def getPartitionReaders(
      blockMetadata: java.lang.Iterable[ShuffleBlockInfo]): java.lang.Iterable[InputStream] = {

    if (blockMetadata.asScala.isEmpty) {
      Iterable.empty.asJava
    } else {
      val minMaxReduceIds = blockMetadata.asScala.map(block => block.getReduceId)
        .foldLeft(Int.MaxValue, 0) {
          case ((min, max), elem) => (math.min(min, elem), math.max(max, elem))
        }
      val minReduceId = minMaxReduceIds._1
      val maxReduceId = minMaxReduceIds._2
      val shuffleId = blockMetadata.asScala.head.getShuffleId

      new ShuffleBlockFetcherIterable(
        TaskContext.get(),
        blockManager,
        serializerManager.wrapStream,
        maxBytesInFlight,
        maxReqsInFlight,
        maxBlocksInFlightPerAddress,
        maxReqSizeShuffleToMem,
        detectCorrupt,
        shuffleMetrics = TaskContext.get().taskMetrics().createTempShuffleReadMetrics(),
        minReduceId,
        maxReduceId,
        shuffleId,
        mapOutputTracker
      ).asJava
    }
  }
}

private class ShuffleBlockFetcherIterable(
    context: TaskContext,
    blockManager: BlockManager,
    streamWrapper: (BlockId, InputStream) => InputStream,
    maxBytesInFlight: Long,
    maxReqsInFlight: Int,
    maxBlocksInFlightPerAddress: Int,
    maxReqSizeShuffleToMem: Long,
    detectCorrupt: Boolean,
    shuffleMetrics: ShuffleReadMetricsReporter,
    minReduceId: Int,
    maxReduceId: Int,
    shuffleId: Int,
    mapOutputTracker: MapOutputTracker) extends Iterable[InputStream] {

  override def iterator: Iterator[InputStream] =
    new ShuffleBlockFetcherIterator(
      context,
      blockManager.shuffleClient,
      blockManager,
      mapOutputTracker.getMapSizesByExecutorId(shuffleId, minReduceId, maxReduceId + 1),
      streamWrapper,
      maxBytesInFlight,
      maxReqsInFlight,
      maxBlocksInFlightPerAddress,
      maxReqSizeShuffleToMem,
      detectCorrupt,
      shuffleMetrics).toCompletionIterator.map(_._2)
}
