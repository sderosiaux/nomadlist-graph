package com.sderosiaux.scrapper

import java.net.URI

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Keep, Sink, Source, SourceQueueWithComplete}
import akka.util.ByteString
import cats.effect.{Bracket, ExitCode, IO, IOApp}
import com.softwaremill.sttp._
import com.softwaremill.sttp.akkahttp.AkkaHttpBackend
import cats.implicits._
import io.circe.Decoder.Result
import io.circe._
import io.circe.generic.semiauto._
import io.circe.parser._
import io.circe.syntax._
import io.circe.optics.JsonPath._

import scala.concurrent.Future
import scala.util.{Failure, Random, Success}

final case class AkkaStuff(system: ActorSystem, backend: SttpBackend[Future, Source[ByteString, Any]], mat: ActorMaterializer)

final case class TinyUser(username: String) extends AnyVal
final case class Nomad(
                username: String,
                following: List[String],
                followers: List[String]
                )

object Hallo extends App {

  implicit val as = ActorSystem()
  implicit val mat = ActorMaterializer()
  implicit val ec = as.dispatcher

  val (queue: SourceQueueWithComplete[Int], done) = Source.queue[Int](100, OverflowStrategy.backpressure)
    .mapAsync(10)(s => {
      if (s < 100000) queue.offer(s + 1) else queue.complete()
      Future.successful(s)
    })
    .toMat(Sink.foreach(println(_)))(Keep.both)
    .run()

    done.onComplete { _ => as.terminate() }

    queue.offer(1) // start the process
}

object FetchNomadUsers extends IOApp {
  val fetchedUsers = collection.mutable.ListBuffer[String]()

  implicit val tinyUserDecoder: Decoder[TinyUser] = deriveDecoder
  //implicit val nomadDecoder: Decoder[Nomad] = deriveDecoder
  implicit val nomadDecoder: Decoder[Nomad] = (c: HCursor) => {
    // instead of
    // c.downField("following").as[Map[String, TinyUser]].map(_.values.map(_.username).toList)
    // we use optics (and avoid casting to Map)
    val following = root.following.each.username.string
    val followers = root.followers.each.username.string

    // but
    // following <- c.focus.map(following.getAll)
    // where is the decodingFailure ???

    for {
      username <- c.downField("username").as[String]
      following <- Either.fromOption(c.focus.map(following.getAll), DecodingFailure("boom", List()))
      followers <- Either.fromOption(c.focus.map(followers.getAll), DecodingFailure("boom", List()))
    } yield Nomad(username, following, followers)
  }

  def userUrl(username: String): Uri = uri"https://nomadlist.com/@$username.json"
  val initialUrl: Uri = userUrl("mrty") // levelsio

  def parseJson(str: String): Unit = {
    val nomad = decode[Nomad](str)
    nomad match {
      case Left(value) =>
      case Right(value) =>
        fetchedUsers += value.username
        println(value.username, value.followers.length, value.following.length)
    }
  }

  def process(res: Source[ByteString, Any])(implicit mat: ActorMaterializer): IO[Unit] = {
    val eventualDone = res.fold("")((res, bs) => res + bs.decodeString("UTF-8"))
    IO.fromFuture(IO.pure(eventualDone.runForeach(parseJson))) *> IO.unit
  }

  override def run(args: List[String]): IO[ExitCode] = {
    val as = ActorSystem("FetchNomadUsers")
    val akkaStuff = AkkaStuff(
      as,
      AkkaHttpBackend.usingActorSystem(as),
      ActorMaterializer()(as)
    )
    IO.pure(akkaStuff)
      .bracket(startLookup(_, initialUrl))(as => (IO.fromFuture(IO(as.system.terminate())) *> IO(as.backend.close())).void)
  }

  def lookup(as: AkkaStuff, uri: Uri): IO[Unit] = {
    implicit val bck = as.backend
    implicit val mat = as.mat

    def doCall: Future[Response[Source[ByteString, Any]]] = sttp.get(uri)
      .response(asStream[Source[ByteString, Any]])
      .send()

    for {
      r <- IO.fromFuture(IO(doCall))
      ex <- r.body match {
        case Left(msg) => IO.raiseError(new Throwable(msg))
        case Right(res) => process(res)
      }
    } yield ex
  }

  def startLookup(as: AkkaStuff, uri: Uri): IO[ExitCode] = {
    lookup(as, uri).attempt.flatMap {
      case Left(value) => IO(println(s"ERR: ${value.getMessage}")).as(ExitCode.Error)
      case Right(_) => IO.pure(ExitCode.Success)
    }
  }
}
