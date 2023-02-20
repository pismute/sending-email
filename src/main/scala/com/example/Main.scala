package com.example

import cats.effect.IO
import cats.effect.IOApp
import cats.effect.kernel.Resource
import cats.implicits._
import io.confluent.kafka.schemaregistry.client._
import io.confluent.kafka.serializers._
import io.confluent.kafka.streams.serdes.avro._
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.TopologyTestDriver
import org.apache.kafka.streams.kstream.Named
import org.apache.kafka.streams.scala._
import org.apache.kafka.streams.scala.kstream._
import org.apache.kafka.streams.scala.serialization.Serdes

import java.util.Properties
import java.util.UUID
import scala.jdk.CollectionConverters._

import ImplicitConversions._
import Serdes._

object resources {
  def testDriver(topology: Topology): Resource[IO, TopologyTestDriver] =
    Resource.fromAutoCloseable(IO.pure {
      // setup test driver
      val props = new Properties();
      props.put(StreamsConfig.APPLICATION_ID_CONFIG, "test")
      props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:1234")
      props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2)
      new TopologyTestDriver(topology, props)
    })
}

object serdes {
  val schemaRegistryClient: MockSchemaRegistryClient = new MockSchemaRegistryClient()

  val avroConfig = Map(AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG -> "mock://localhost:8081").asJava

  implicit val avroSerdeForSendEmailRequest: SpecificAvroSerde[SendEmailRequest] = {
    val value = new SpecificAvroSerde[SendEmailRequest](schemaRegistryClient)
    value.configure(avroConfig, false)
    value
  }

  implicit val avroSerdeForSendEmailSent: SpecificAvroSerde[SendEmailSent] = {
    val value = new SpecificAvroSerde[SendEmailSent](schemaRegistryClient)
    value.configure(avroConfig, false)
    value
  }

  implicit val avroSerdeForSendEmailFailed: SpecificAvroSerde[SendEmailFailed] = {
    val value = new SpecificAvroSerde[SendEmailFailed](schemaRegistryClient)
    value.configure(avroConfig, false)
    value
  }
  
  implicit val avroSerdeForSendEmail: SpecificAvroSerde[SendEmail] = {
    val value = new SpecificAvroSerde[SendEmail](schemaRegistryClient)
    value.configure(avroConfig, false)
    value
  }  
}

object models {
  def toSendEmail(request: SendEmailRequest, status: EmailStatus) = 
    SendEmail(
      id = request.id,
      correlationId = request.correlationId,
      correlationType = request.correlationType,
      email = request.email,
      body = request.body,
      emailStatus = status
    )

  def toFailed(request: SendEmailRequest) = 
    SendEmailFailed(
      id = request.id,
      correlationId = request.correlationId,
      correlationType = request.correlationType,
      email = request.email,
      body = request.body
    )

  def toSent(request: SendEmailRequest) = 
    SendEmailSent(
      id = request.id,
      correlationId = request.correlationId,
      correlationType = request.correlationType
    )

  def sendEmailRequest(email: String, body: String): SendEmailRequest =
    SendEmailRequest(
      id = UUID.randomUUID(),
      correlationId = UUID.randomUUID(),
      correlationType = CorrelationType.M,
      email = email,
      body = body,
    )
  
}

object topologies {
  import serdes._

  implicit val consumedForSendEmailRequest = Consumed.`with`[UUID, SendEmailRequest]

  def sendingEmail: Topology = {
    val builder = new StreamsBuilder()
    builder
      .stream[UUID, SendEmailRequest]("email-requested")
      .split(Named.as("email-requested-branch-"))
      .branch(
        (_, v) => v.email == "failed", 
        Branched.withConsumer(_.mapValues(models.toFailed _).to("email-failed")
          , "failed"
        )
      )
      .defaultBranch(
        Branched.withConsumer(_.mapValues(models.toSent _).to("email-sent"),
          "sent"
        )
      )

    builder.build()
  }
}

object Main extends IOApp.Simple {

  def testSedingEmail: IO[String] = {
    resources.testDriver(topologies.sendingEmail).use { testDriver =>
      IO {
        val emailRequestedInput  =
          testDriver.createInputTopic[UUID, SendEmailRequest](
            "email-requested",
            uuidSerde.serializer(),
            serdes.avroSerdeForSendEmailRequest.serializer()
          )
        val emailFailedOutput =
          testDriver.createOutputTopic(
            "email-failed",
            stringSerde.deserializer(),
            serdes.avroSerdeForSendEmailFailed.deserializer()
          )
        val emailSentOutput =
          testDriver.createOutputTopic(
            "email-sent",
            stringSerde.deserializer(),
            serdes.avroSerdeForSendEmailSent.deserializer()
          )

        val failed = models.sendEmailRequest("failed", "failed")
        val a = models.sendEmailRequest("a", "a")
        
        emailRequestedInput.pipeInput(a.id, a)
        emailRequestedInput.pipeInput(failed.id, failed)

        emailFailedOutput.readKeyValuesToList().toString
//        emailSentOutput.readKeyValuesToList().toString
      }
    }
  }

  def run: IO[Unit] =
    testSedingEmail >>= IO.println
}
