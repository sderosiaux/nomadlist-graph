package com.sderosiaux.scrapper

import io.circe.optics.JsonPath.root
import io.circe.parser.decode
import io.circe.{Decoder, DecodingFailure, HCursor}
import io.circe.optics.JsonPath._
import io.circe._
import io.circe.generic.semiauto._
import io.circe.parser._
import io.circe.syntax._
import cats.implicits._

object Nomad {
  implicit val nomadDecoder: Decoder[Nomad] = (c: HCursor) => {
    val following = root.following.each.username.string
    val followers = root.followers.each.username.string

    for {
      username <- c.downField("username").as[String].map(_.substring(1)) // "@"
      photo <- c.downField("photo").as[String]
      following <- Either.fromOption(c.focus.map(following.getAll), DecodingFailure("boom", List())) // oops
      followers <- Either.fromOption(c.focus.map(followers.getAll), DecodingFailure("boom", List())) // oops
    } yield Nomad(username, photo, following, followers)
  }

  def fromJson(str: String): Either[Throwable, Nomad] = {
    decode[Nomad](str).leftMap(_.fillInStackTrace())
  }
}

final case class Nomad(
                username: String,
                photo: String,
                following: List[String],
                followers: List[String]
                )
