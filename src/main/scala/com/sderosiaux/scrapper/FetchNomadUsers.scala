package com.sderosiaux.scrapper

import java.nio.file.Paths

import akka.actor.ActorSystem
import akka.stream.scaladsl.{FileIO, Keep, Sink, Source, SourceQueueWithComplete}
import akka.stream.{ActorMaterializer, IOResult, OverflowStrategy, QueueOfferResult}
import akka.util.ByteString
import cats.effect.{ExitCode, IO, IOApp}
import cats.implicits._
import com.softwaremill.sttp._
import com.softwaremill.sttp.akkahttp.AkkaHttpBackend
import io.circe.syntax._

import scala.concurrent.Future
import scala.util.{Failure, Success}

final case class AkkaStuff(system: ActorSystem, backend: SttpBackend[Future, Source[ByteString, Any]], mat: ActorMaterializer)


object FetchNomadUsers extends IOApp {
  import NomadList._

  val output = "front-end/nomads.json" // it's the front-end that uses it

  override def run(args: List[String]): IO[ExitCode] = {

    // Tons of crap needed for Akka/Streams
    implicit val system = ActorSystem("FetchNomadUsers")
    val akkaStuff = AkkaStuff(
      system,
      AkkaHttpBackend.usingActorSystem(system),
      ActorMaterializer()
    )

    // stop the system to not hang the main thread
    IO.pure(akkaStuff)
      .bracket(startFetching(_, initialUrl))(as => IO.fromFuture(IO(as.system.terminate())).void)
  }

  /**
    * We start with one url, and we'll go from there.
    */
  def startFetching(as: AkkaStuff, uri: Uri): IO[ExitCode] = {

    implicit val mat = as.mat
    implicit val ec = as.system.dispatcher

    // we reactive-write into our output file
    val sink: Sink[ByteString, Future[IOResult]] = FileIO.toPath(Paths.get(output))

    val (queue: SourceQueueWithComplete[String], done) = Source.queue[String](100000000, OverflowStrategy.backpressure)
      .mapMaterializedValue(q => { qq = q; q }) // super trick to access the queue from inside (statefulMapConcat)
      .mapAsync(5)(lookupUser(as, _))
      .collect { case Right(user) => user } // we ignore the errors: none
      .statefulMapConcat { () =>
        // pretty sure it's not needed to have a concurrent set, one thread at a time here, who cares
        val fetchedUsers = Sets.createSet[String]()
        val allUsers = Sets.createSet[String]()
        fetchedUsers += initialUser
        allUsers += initialUser

        nomad => {
          println(s"fetched: ${nomad.username}")
          val toQueue = nomad.following ++ nomad.followers
          allUsers ++= toQueue
          val rest = toQueue.toSet.diff(fetchedUsers)
          if (rest.isEmpty) {
            // need a way to quit properly when we visit every user
            // if ((allUsers -- fetchedUsers).isEmpty) {
            //   qq.complete()
            // }
          } else {
            rest.foreach { username =>
              fetchedUsers += username // disgusting
              qq.offer(username).onComplete {
                case Success(s: QueueOfferResult) => println("Queued:" + s)
                case Failure(ex) => println("ERR: ", ex)
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

    val exit = IO.fromFuture(IO.pure(done)).attempt.map {
      case Left(_) => ExitCode.Error
      case Right(_) => ExitCode.Success
    }

    // START!
    queue.offer(initialUser)

    exit
  }

  def lookupUser(as: AkkaStuff, username: String): Future[Either[Throwable, Nomad]] = {
    // crap
    implicit val bck = as.backend
    implicit val mat = as.mat
    implicit val ec = as.system.dispatcher

    sttp.get(userUrl(username))
      .response(asString)
      .send()
      .map(_.body
        .leftMap(new Throwable(_))
        .flatMap(Nomad.fromJson)
      )
  }

  // super trick to access the queue from inside
  var qq: SourceQueueWithComplete[String] = _
}
