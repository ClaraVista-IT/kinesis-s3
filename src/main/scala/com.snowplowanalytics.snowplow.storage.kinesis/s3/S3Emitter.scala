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

import scala.collection.JavaConverters._

// Java libs
import java.io.{
  OutputStream,
  DataOutputStream,
  ByteArrayInputStream,
  ByteArrayOutputStream,
  IOException
}
import java.util.Calendar
import java.text.SimpleDateFormat

// Java lzo
import org.apache.hadoop.conf.Configuration
import com.hadoop.compression.lzo.LzopCodec

// Elephant bird
import com.twitter.elephantbird.mapreduce.io.{
  ThriftBlockWriter
}

// Logging
import org.apache.commons.logging.{Log,LogFactory}

// AWS libs
import com.amazonaws.AmazonServiceException
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.ObjectMetadata

// AWS Kinesis connector libs
import com.amazonaws.services.kinesis.connectors.{
  UnmodifiableBuffer,
  KinesisConnectorConfiguration
}
import com.amazonaws.services.kinesis.connectors.interfaces.IEmitter

// Scala
import scala.collection.JavaConversions._
import scala.util.control.NonFatal
import scala.annotation.tailrec

// Scalaz
import scalaz._
import Scalaz._

// json4s
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.JsonDSL._

// Joda-Time
import org.joda.time.{DateTime, DateTimeZone}
import org.joda.time.format.DateTimeFormat

// Tracker
import com.snowplowanalytics.snowplow.scalatracker.Tracker

// This project
import sinks._

/**
 * Emitter for flushing Kinesis event data to S3.
 *
 * Once the buffer is full, the emit function is called.
 */
class S3Emitter(config: KinesisConnectorConfiguration, badSink: ISink, tracker: Option[Tracker]) extends IEmitter[ EmitterInput ] {
   println ("S3EMMITTEER")
  /**
   * The amount of time to wait in between unsuccessful index requests (in milliseconds).
   * 10 seconds = 10 * 1000 = 10000
   */
  private val BackoffPeriod = 10000

  // An ISO valid timestamp formatter
  private val TstampFormat = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(DateTimeZone.UTC)

  val bucket = config.S3_BUCKET
  val log = LogFactory.getLog(classOf[S3Emitter])
  val client = new AmazonS3Client(config.AWS_CREDENTIALS_PROVIDER)
  client.setEndpoint(config.S3_ENDPOINT)

  val dateFormat = new SimpleDateFormat("yyyy/MM/dd/yyyy-MM-dd-HH-mm-ss")

  /**
   * Determines the filename in S3, which is the corresponding
   * Kinesis sequence range of records in the file.
   */
  protected def getFileName(firstSeq: String, lastSeq: String, lzoCodec: LzopCodec): String = {
    println("    ******  "+"LZO/"+dateFormat.format(Calendar.getInstance().getTime()) +
      "-" + firstSeq + "-" + lastSeq + lzoCodec.getDefaultExtension())


    "LZO/"+dateFormat.format(Calendar.getInstance().getTime()) +
      "-" + firstSeq + "-" + lastSeq + lzoCodec.getDefaultExtension()
  }

  /**
   * Reads items from a buffer and saves them to s3.
   *
   * This method is expected to return a List of items that
   * failed to be written out to S3, which will be sent to
   * a Kinesis stream for bad events.
   *
   * @param buffer BasicMemoryBuffer containing EmitterInputs
   * @return list of inputs which failed transformation
   */
  override def emit(buffer: UnmodifiableBuffer[ EmitterInput ]): java.util.List[ EmitterInput ] = {
    println(" ****************************** emittting !!!!")

    log.info(s"Flushing buffer with ${buffer.getRecords.size} records.")


    val records = buffer.getRecords().asScala.toList

    val (outputStream, indexOutputStream, lzoCodec, results) = LzoSerializer.serialize(records)

    val filename = getFileName(buffer.getFirstSequenceNumber, buffer.getLastSequenceNumber, lzoCodec)
    val indexFilename = filename + ".index"

    val obj = new ByteArrayInputStream(outputStream.toByteArray)
    val indexObj = new ByteArrayInputStream(indexOutputStream.toByteArray)

    val objMeta = new ObjectMetadata()
    val indexObjMeta = new ObjectMetadata()

    objMeta.setContentLength(outputStream.size)
    indexObjMeta.setContentLength(indexOutputStream.size)

    val (successes, failures) = results.partition(_.isSuccess)

    log.info(s"Successfully serialized ${successes.size} records out of ${successes.size + failures.size}")

    /**
     * Keep attempting to send the data to S3 until it succeeds
     *
     * @return list of inputs which failed to be sent to S3
     */
    def attemptEmit(): List[EmitterInput] = {
      while (true) {
        try {
          client.putObject(bucket, filename, obj, objMeta)
          client.putObject(bucket, indexFilename, indexObj, indexObjMeta)

          log.error(" ***********************  attemptEmitting ")

          log.info(s"Successfully emitted ${successes.size} records to S3 in s3://${bucket}/${filename} with index $indexFilename")

          // Return the failed records
          return failures

        } catch {
          // Retry on failure
          case ase: AmazonServiceException => {
            log.error("S3 could not process the request", ase)
            tracker match {
              case Some(t) => SnowplowTracking.sendFailureEvent(t, BackoffPeriod, ase.toString)
              case None => None
            }
            sleep(BackoffPeriod)
          }
          case NonFatal(e) => {
            log.error("S3Emitter threw an unexpected exception", e)
            tracker match {
              case Some(t) => SnowplowTracking.sendFailureEvent(t, BackoffPeriod, e.toString)
              case None => None
            }
            sleep(BackoffPeriod)
          }
        }
      }

      Nil // should never be reached
    }

    if (successes.size > 0) {
      attemptEmit()
    } else {
      failures
    }
  }

  /**
   * Closes the client when the KinesisConnectorRecordProcessor is shut down
   */
  override def shutdown() {
    client.shutdown
  }

  /**
   * Sends records which fail deserialization or compression
   * to Kinesis with an error message
   *
   * @param records List of failed records to send to Kinesis
   */
  override def fail(records: java.util.List[ EmitterInput ]) {
    // TODO: Should there be a check for Successes?
    for (Failure(record) <- records.toList) {
      log.warn(s"Record failed: $record.line")
      log.info("Sending failed record to Kinesis")
      val output = compact(render(
        ("line" -> record.line) ~ 
        ("errors" -> record.errors) ~
        ("failure_tstamp" -> getTimestamp(System.currentTimeMillis()))
      ))
      badSink.store(output, Some("key"), false)
    }
  }

  /**
   * Period between retrying sending events to S3
   *
   * @param sleepTime Length of time between tries
   */
  private def sleep(sleepTime: Long): Unit = {
    try {
      Thread.sleep(sleepTime)
    } catch {
      case e: InterruptedException => ()
    }
  }

  /**
   * Returns an ISO valid timestamp
   *
   * @param tstamp The Timestamp to convert
   * @return the formatted Timestamp
   */
  private def getTimestamp(tstamp: Long): String = {
    val dt = new DateTime(tstamp)
    TstampFormat.print(dt)
  }
}
