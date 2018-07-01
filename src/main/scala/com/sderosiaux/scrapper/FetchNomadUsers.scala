package com.sderosiaux.scrapper

import java.net.URI
import java.nio.file.{FileSystem, FileSystems, Paths}

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri.Path
import akka.stream.{ActorMaterializer, IOResult, OverflowStrategy, QueueOfferResult}
import akka.stream.scaladsl.{FileIO, Keep, Sink, Source, SourceQueueWithComplete}
import akka.util.ByteString
import cats.effect.IO.{Async, Bind, ContextSwitch, Delay, Map, Pure, RaiseError, Suspend}
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
                photo: String,
                following: List[String],
                followers: List[String]
                )
final case class NomadExport(
                              username: String,
                              photo: String,
                              followers: List[String]
                            )
object NomadExport {
  import io.scalaland.chimney.dsl._
  def fromNomad(n: Nomad) = n.into[NomadExport].transform
}

object Nomad {
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
      username <- c.downField("username").as[String].map(_.substring(1)) // "@"
      photo <- c.downField("photo").as[String]
      following <- Either.fromOption(c.focus.map(following.getAll), DecodingFailure("boom", List()))
      followers <- Either.fromOption(c.focus.map(followers.getAll), DecodingFailure("boom", List()))
    } yield Nomad(username, photo, following, followers)
  }

  def fromJson(str: String): Either[Throwable, Nomad] = {
    decode[Nomad](str).leftMap(_.fillInStackTrace())
  }
}

object FetchNomadUsers extends IOApp {



  implicit val nomadExportEncoder: Encoder[NomadExport] = deriveEncoder

  def userUrl(username: String): Uri = uri"https://nomadlist.com/@$username.json"
  val initialUser: String = "mrty"
  val initialUrl: Uri = userUrl(initialUser) // levelsio



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

  def lookupUser(as: AkkaStuff, username: String): Future[Either[Throwable, Nomad]] = {
    implicit val bck = as.backend
    implicit val mat = as.mat
    implicit val ec = as.system.dispatcher

    def doCall: Future[Response[String]] = sttp.get(userUrl(username))
      .response(asString)
      .send()

    doCall.map(_.body
      .leftMap(new Throwable(_))
      .flatMap(Nomad.fromJson)
    )
  }

  var qq: SourceQueueWithComplete[String] = _

  def startLookup(as: AkkaStuff, uri: Uri): IO[ExitCode] = {

    implicit val mat = as.mat
    implicit val ec = as.system.dispatcher

    val sink: Sink[ByteString, Future[IOResult]] = FileIO.toPath(Paths.get("nomads.json"))

    def createSet[T]() = {
      import scala.collection.JavaConverters._
      java.util.Collections.newSetFromMap(
        new java.util.concurrent.ConcurrentHashMap[T, java.lang.Boolean]).asScala
    }

    val (queue: SourceQueueWithComplete[String], done) = Source.queue[String](100000000, OverflowStrategy.backpressure)
      .mapMaterializedValue(q => { qq = q; q })
      .mapAsync(5)(lookupUser(as, _))
      .collect { case Right(user) => user }
      .statefulMapConcat { () =>
        val fetchedUsers = createSet[String]()
        val allUsers = createSet[String]()
        fetchedUsers += initialUser
        allUsers += initialUser

        nomad => {
          println("fetched: " + nomad.username)
          val toQueue = nomad.following ++ nomad.followers
          allUsers ++= toQueue
          val rest = toQueue.toSet.diff(fetchedUsers)
          if (rest.isEmpty) {
//            if ((allUsers -- fetchedUsers).isEmpty) {
//              qq.complete() // need a way to quit properly when we visit every user
//            }
          } else {
            rest.foreach { username =>
              fetchedUsers += username
              qq.offer(username).onComplete {
                case Success(s: QueueOfferResult) => println("queued:" + s)
                case Failure(ex) => println("ERR?", ex)
              }
            }
          }
          List(nomad)
        }
      }
      .map(NomadExport.fromNomad)
      .map(n => ByteString.fromString(n.asJson.toString()))
      .intersperse(ByteString.fromString("["), ByteString.fromString(", "), ByteString.fromString("]"))
      .toMat(sink)(Keep.both)
      .run()

    done.onComplete { _ => as.system.terminate() }

    val exit = IO.fromFuture(IO.pure(done)).attempt.map {
      case Left(_) => ExitCode.Error
      case Right(_) => ExitCode.Success
    }

    // START!
    queue.offer(initialUser)

    exit
  }
}
