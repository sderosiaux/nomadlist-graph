package com.sderosiaux.scrapper

import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

object NomadExport {
  import io.scalaland.chimney.dsl._
  def fromNomad(n: Nomad) = n.into[NomadExport].transform

  implicit val nomadExportEncoder: Encoder[NomadExport] = deriveEncoder
}

final case class NomadExport(
                              username: String,
                              photo: String,
                              followers: List[String]
                            )


