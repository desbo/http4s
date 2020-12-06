/*
 * Copyright 2013-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s
package server
package staticcontent

import cats.effect.IO
import fs2._
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import org.http4s.syntax.all._

private[staticcontent] trait StaticContentShared { this: Http4sSuite =>
  def routes: HttpRoutes[IO]

  lazy val testResource: Chunk[Byte] = {
    val s = getClass.getResourceAsStream("/testresource.txt")
    require(s != null, "Couldn't acquire resource!")
    val bytes = scala.io.Source
      .fromInputStream(s)
      .mkString
      .getBytes(StandardCharsets.UTF_8)

    Chunk.bytes(bytes)
  }

  lazy val testResourceGzipped: Chunk[Byte] = {
    val url = getClass.getResource("/testresource.txt.gz")
    require(url != null, "Couldn't acquire resource!")
    val bytes = Files.readAllBytes(Paths.get(url.toURI))

    Chunk.bytes(bytes)
  }

  lazy val testWebjarResource: Chunk[Byte] = {
    val s =
      getClass.getResourceAsStream("/META-INF/resources/webjars/test-lib/1.0.0/testresource.txt")
    require(s != null, "Couldn't acquire resource!")

    Chunk.bytes(
      scala.io.Source
        .fromInputStream(s)
        .mkString
        .getBytes(StandardCharsets.UTF_8))
  }

  lazy val testWebjarSubResource: Chunk[Byte] = {
    val s = getClass.getResourceAsStream(
      "/META-INF/resources/webjars/test-lib/1.0.0/sub/testresource.txt")
    require(s != null, "Couldn't acquire resource!")

    Chunk.bytes(
      scala.io.Source
        .fromInputStream(s)
        .mkString
        .getBytes(StandardCharsets.UTF_8))
  }

  def runReq(req: Request[IO]): IO[(IO[Chunk[Byte]], Response[IO])] =
    routes.orNotFound(req).map { resp =>
      (resp.body.compile.to(Array).map(Chunk.bytes), resp)
    }

}
