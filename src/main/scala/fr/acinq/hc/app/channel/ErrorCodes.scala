package fr.acinq.hc.app.channel

import fr.acinq.eclair.wire.Error
import scodec.bits.ByteVector


object ErrorCodes {
  final val ERR_HOSTED_WRONG_BLOCKDAY = "0001"
  final val ERR_HOSTED_WRONG_LOCAL_SIG = "0002"
  final val ERR_HOSTED_WRONG_REMOTE_SIG = "0003"
  final val ERR_HOSTED_TOO_MANY_STATE_UPDATES = "0005"
  final val ERR_HOSTED_TIMED_OUT_OUTGOING_HTLC = "0006"
  final val ERR_HOSTED_IN_FLIGHT_HTLC_IN_RESTORE = "0007"
  final val ERR_HOSTED_IN_FLIGHT_HTLC_IN_SYNC = "0008"
  final val ERR_HOSTED_CHANNEL_DENIED = "0009"

  val knownHostedCodes: Map[String, String] = Map (
    ERR_HOSTED_WRONG_BLOCKDAY -> "ERR_HOSTED_WRONG_BLOCKDAY",
    ERR_HOSTED_WRONG_LOCAL_SIG -> "ERR_HOSTED_WRONG_LOCAL_SIG",
    ERR_HOSTED_WRONG_REMOTE_SIG -> "ERR_HOSTED_WRONG_REMOTE_SIG",
    ERR_HOSTED_TOO_MANY_STATE_UPDATES -> "ERR_HOSTED_TOO_MANY_STATE_UPDATES",
    ERR_HOSTED_TIMED_OUT_OUTGOING_HTLC -> "ERR_HOSTED_TIMED_OUT_OUTGOING_HTLC",
    ERR_HOSTED_IN_FLIGHT_HTLC_IN_RESTORE -> "ERR_HOSTED_IN_FLIGHT_HTLC_IN_RESTORE",
    ERR_HOSTED_IN_FLIGHT_HTLC_IN_SYNC -> "ERR_HOSTED_IN_FLIGHT_HTLC_IN_SYNC",
    ERR_HOSTED_CHANNEL_DENIED -> "ERR_HOSTED_CHANNEL_DENIED"
  )
}

object ErrorExt {
  def generateFrom(error: Error): ErrorExt = {
    val stamp = new java.util.Date(System.currentTimeMillis).toString
    val text = extractDescription(error)
    ErrorExt(error, stamp, text)
  }

  def extractDescription(error: Error): String = {
    val postTagData = error.data.drop(2)
    val tag = error.data.take(2)

    ErrorCodes.knownHostedCodes.get(tag.toHex) match {
      case Some(codeOnly) if postTagData.isEmpty => s"hosted-code=$codeOnly"
      case Some(code) => s"hosted-code=$code, extra=${error.copy(data = postTagData).toAscii}"
      case None => error.toAscii
    }
  }
}

case class ErrorExt(error: Error, stamp: String, description: String)
