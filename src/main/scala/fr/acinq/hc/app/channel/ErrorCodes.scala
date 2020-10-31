package fr.acinq.hc.app.channel

import fr.acinq.eclair.wire.Error
import scodec.bits.ByteVector


object ErrorCodes {
  final val ERR_HOSTED_WRONG_BLOCKDAY = ByteVector.fromValidHex("0001")
  final val ERR_HOSTED_WRONG_LOCAL_SIG = ByteVector.fromValidHex("0002")
  final val ERR_HOSTED_WRONG_REMOTE_SIG = ByteVector.fromValidHex("0003")
  final val ERR_HOSTED_TOO_MANY_STATE_UPDATES = ByteVector.fromValidHex("0005")
  final val ERR_HOSTED_TIMED_OUT_OUTGOING_HTLC = ByteVector.fromValidHex("0006")
  final val ERR_HOSTED_IN_FLIGHT_HTLC_IN_RESTORE = ByteVector.fromValidHex("0007")
  final val ERR_HOSTED_IN_FLIGHT_HTLC_IN_SYNC = ByteVector.fromValidHex("0008")
  final val ERR_HOSTED_CHANNEL_DENIED = ByteVector.fromValidHex("0009")

  val knownHostedCodes: Map[ByteVector, String] = Map (
    ERR_HOSTED_WRONG_BLOCKDAY -> "ERR_HOSTED_WRONG_BLOCKDAY",
    ERR_HOSTED_WRONG_LOCAL_SIG -> "ERR_HOSTED_WRONG_LOCAL_SIG",
    ERR_HOSTED_WRONG_REMOTE_SIG -> "ERR_HOSTED_WRONG_REMOTE_SIG",
    ERR_HOSTED_TOO_MANY_STATE_UPDATES -> "ERR_HOSTED_TOO_MANY_STATE_UPDATES",
    ERR_HOSTED_TIMED_OUT_OUTGOING_HTLC -> "ERR_HOSTED_TIMED_OUT_OUTGOING_HTLC",
    ERR_HOSTED_IN_FLIGHT_HTLC_IN_RESTORE -> "ERR_HOSTED_IN_FLIGHT_HTLC_IN_RESTORE",
    ERR_HOSTED_IN_FLIGHT_HTLC_IN_SYNC -> "ERR_HOSTED_IN_FLIGHT_HTLC_IN_SYNC",
    ERR_HOSTED_CHANNEL_DENIED -> "ERR_HOSTED_CHANNEL_DENIED"
  )

  case class ErrorExt(error: Error) {
    val tag: ByteVector = error.data.take(2)

    val postTagData: ByteVector = error.data.drop(2)

    def toHostedAscii: String = knownHostedCodes.get(tag) match {
      case Some(code) if postTagData.isEmpty => s"hosted-code=$code"
      case Some(code) => s"hosted-code=$code, extra=${error.copy(data = postTagData).toAscii}"
      case None => error.toAscii
    }
  }
}
