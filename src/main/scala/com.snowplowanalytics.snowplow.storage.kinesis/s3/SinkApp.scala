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

// Java
import java.io.File
import java.util.Properties

// Argot
import org.clapper.argot._

// Config
import com.typesafe.config.{Config, ConfigFactory}

// AWS libs
import com.amazonaws.auth.AWSCredentialsProvider

// AWS Kinesis Connector libs
import com.amazonaws.services.kinesis.connectors.KinesisConnectorConfiguration

// Tracker
import com.snowplowanalytics.snowplow.scalatracker.Tracker

// Scalaz
import scalaz._
import Scalaz._

// This project
import sinks._

/**
 * The entrypoint class for the Kinesis-S3 Sink applciation.
 */
object SinkApp extends App {

  // Argument specifications
  import ArgotConverters._

  // General bumf for our app
  val parser = new ArgotParser(
    programName = "generated",
    compactUsage = true,
    preUsage = Some("%s: Version %s. Copyright (c) 2014-2015, %s.".format(
      generated.Settings.name,
      generated.Settings.version,
      generated.Settings.organization)
    )
  )

  // Optional config argument
  val config = parser.option[Config](List("config"),
                                     "filename",
                                     "Configuration file.") {
    (c, opt) =>

      val file = new File(c)
      if (file.exists) {
        ConfigFactory.parseFile(file)
      } else {
        parser.usage("Configuration file \"%s\" does not exist".format(c))
        ConfigFactory.empty()
      }
  }
  parser.parse(args)

  val conf = config.value.getOrElse(throw new RuntimeException("--config argument must be provided"))

  println(" ************************************************ ")

  val tracker = if (conf.hasPath("sink.monitoring.snowplow")) {
    SnowplowTracking.initializeTracker(conf.getConfig("sink.monitoring.snowplow")).some
  } else { 
    None
  }

  println("1")

  // TODO: make the conf file more like the Elasticsearch equivalent
  val kinesisSinkRegion = conf.getConfig("sink").getConfig("kinesis").getString("region")
  val kinesisSinkEndpoint = s"https://kinesis.${kinesisSinkRegion}.amazonaws.com"
  val kinesisSink = conf.getConfig("sink").getConfig("kinesis").getConfig("out")
  val kinesisSinkName = kinesisSink.getString("stream-name")

  val logLevel = conf.getConfig("sink").getConfig("logging").getString("level")
  System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, logLevel)

  println("2")

  val credentialConfig = conf.getConfig("sink").getConfig("aws")

  val credentials = CredentialsLookup.getCredentialsProvider(credentialConfig.getString("access-key"), credentialConfig.getString("secret-key"))

  println("3")


  val badSink = new KinesisSink(credentials, kinesisSinkEndpoint, kinesisSinkName, tracker)

  val executor = new S3SinkExecutor(convertConfig(conf, credentials), badSink, tracker)

  println("4")

  tracker match {
    case Some(t) => SnowplowTracking.initializeSnowplowTracking(t)
    case None => None
  }

  println("5")


  executor.run()
  println("6")


  /**
   * This function converts the config file into the format
   * expected by the Kinesis connector interfaces.
   *
   * @param connector The configuration HOCON
   * @return A KinesisConnectorConfiguration
   */
  def convertConfig(conf: Config, credentials: AWSCredentialsProvider): KinesisConnectorConfiguration = {
    val props = new Properties()
    val connector = conf.resolve.getConfig("sink")

    val kinesis = connector.getConfig("kinesis")
    val kinesisIn = kinesis.getConfig("in")
    val kinesisRegion = kinesis.getString("region")
    val kEndpoint = s"https://kinesis.${kinesisSinkRegion}.amazonaws.com"
    val streamName = kinesisIn.getString("stream-name")
    val initialPosition = kinesisIn.getString("initial-position")
    val appName = kinesis.getString("app-name")

    val s3 = connector.getConfig("s3")
    val s3Region = s3.getString("region")
    val s3Endpoint = s3Region match {
      case "us-east-1" => "https://s3.amazonaws.com"
      case _ => s"https://s3-$s3Region.amazonaws.com"
    }
    val bucket = s3.getString("bucket")

    val buffer = connector.getConfig("buffer")
    val byteLimit = buffer.getString("byte-limit")
    val recordLimit = buffer.getString("record-limit")
    val timeLimit = buffer.getString("time-limit")

    props.setProperty(KinesisConnectorConfiguration.PROP_KINESIS_INPUT_STREAM, streamName)
    props.setProperty(KinesisConnectorConfiguration.PROP_KINESIS_ENDPOINT, kEndpoint)
    props.setProperty(KinesisConnectorConfiguration.PROP_APP_NAME, appName)
    props.setProperty(KinesisConnectorConfiguration.PROP_INITIAL_POSITION_IN_STREAM, initialPosition)

    props.setProperty(KinesisConnectorConfiguration.PROP_S3_ENDPOINT, s3Endpoint)
    props.setProperty(KinesisConnectorConfiguration.PROP_S3_BUCKET, bucket)

    props.setProperty(KinesisConnectorConfiguration.PROP_BUFFER_BYTE_SIZE_LIMIT, byteLimit)
    props.setProperty(KinesisConnectorConfiguration.PROP_BUFFER_RECORD_COUNT_LIMIT, recordLimit)
    props.setProperty(KinesisConnectorConfiguration.PROP_BUFFER_MILLISECONDS_LIMIT, timeLimit)

    props.setProperty(KinesisConnectorConfiguration.PROP_CONNECTOR_DESTINATION, "s3")

    // So that the region of the DynamoDB table is correct
    props.setProperty(KinesisConnectorConfiguration.PROP_REGION_NAME, kinesisRegion)

    // The emit method retries sending to S3 indefinitely, so it only needs to be called once
    props.setProperty(KinesisConnectorConfiguration.PROP_RETRY_LIMIT, "1")

    new KinesisConnectorConfiguration(props, credentials)
  }

}
