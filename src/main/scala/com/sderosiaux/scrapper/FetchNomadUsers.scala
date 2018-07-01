package com.sderosiaux.scrapper

import java.net.URI

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.effect.{Bracket, ExitCode, IO, IOApp}
import com.softwaremill.sttp._
import com.softwaremill.sttp.akkahttp.AkkaHttpBackend
import cats.implicits._

import scala.concurrent.Future

object FetchNomadUsers extends IOApp {
  def userUrl(username: String): Uri = Uri(new URI(s"https://nomadlist.com/@$username.json"))
  val initialUrl: Uri = userUrl("levelsio")

  implicit val as = ActorSystem("FetchNomadUsers")
  implicit val backend = AkkaHttpBackend.usingActorSystem(as)
  implicit val mat = ActorMaterializer()

  def process(res: Source[ByteString, Any]) = {
    val eventualDone = res.runForeach(bs => println(bs.decodeString("UTF-8")))
    IO.fromFuture(IO.pure(eventualDone))
  }

  override def run(args: List[String]): IO[ExitCode] = {
    val resp: Future[Response[Source[ByteString, Any]]] = sttp.get(initialUrl)
      .response(asStream[Source[ByteString, Any]])
      .send()

    for {
      r <- IO.fromFuture(IO.pure(resp))
      ex <- r.body match {
        case Left(msg) => IO(println(s"ERR: $msg")).as(ExitCode.Error)
        case Right(res) => process(res).as(ExitCode.Success)
      }
    } yield ex

  }
}
