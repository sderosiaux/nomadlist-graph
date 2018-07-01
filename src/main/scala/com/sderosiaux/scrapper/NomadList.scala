package com.sderosiaux.scrapper

import com.softwaremill.sttp.Uri
import com.softwaremill.sttp._

object NomadList {
  def userUrl(username: String): Uri = uri"https://nomadlist.com/@$username.json"
  val initialUser: String = "levelsio"
  val initialUrl: Uri = userUrl(initialUser)
}
