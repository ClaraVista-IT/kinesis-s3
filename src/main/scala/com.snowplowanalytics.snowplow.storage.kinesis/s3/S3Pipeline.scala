/*
 * Copyright (c) 2014-2015 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.storage.kinesis.s3

// AWS Kinesis Connector libs
import com.amazonaws.services.kinesis.connectors.interfaces.{
  IEmitter,
  IBuffer,
  ITransformer,
  IFilter,
  IKinesisConnectorPipeline
}
import com.amazonaws.services.kinesis.connectors.KinesisConnectorConfiguration
import com.amazonaws.services.kinesis.connectors.impl.{BasicMemoryBuffer,AllPassFilter}

// Tracker
import com.snowplowanalytics.snowplow.scalatracker.Tracker

// This project
import sinks._

/**
 * S3Pipeline class sets up the Emitter/Buffer/Transformer/Filter
 */
class S3Pipeline(badSink: ISink, tracker: Option[Tracker]) extends IKinesisConnectorPipeline[ ValidatedRecord, EmitterInput ] {
  println("init s3 pipe")

  override def getEmitter(configuration: KinesisConnectorConfiguration) = {
    println("get emiter")
    new S3Emitter(configuration, badSink, tracker)
  }

  override def getBuffer(configuration: KinesisConnectorConfiguration) ={
    println("get buffer")
    new BasicMemoryBuffer[ValidatedRecord](configuration)
  }

  override def getTransformer(c: KinesisConnectorConfiguration) = {
    println("get raw event transformer")
    new RawEventTransformer()
  }

  override def getFilter(c: KinesisConnectorConfiguration) = {
    println("get filter")
    new AllPassFilter[ValidatedRecord]()
  }

}

