package org.orbeon.facades

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal
import scala.scalajs.js.typedarray.Uint8Array


@js.native
@JSGlobal("TextDecoder")
class TextDecoder extends js.Object {
  def decode(buffer: Uint8Array): String = js.native
}

@js.native
@JSGlobal("TextEncoder")
class TextEncoder extends js.Object {
  def encode(value: String): Uint8Array = js.native
}