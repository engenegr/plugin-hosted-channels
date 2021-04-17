package fr.acinq.hc.app.db

import slick.jdbc.PostgresProfile.api._
import fr.acinq.bitcoin.{ByteVector32, Crypto}
import slick.jdbc.PostgresProfile
import scodec.bits.ByteVector


class PreimagesDb (val db: PostgresProfile.backend.Database) {
  def findByHash(hash: ByteVector32): Option[ByteVector32] = Blocking.txRead(Preimages.findByHash(hash.toArray).result.headOption, db).map(ByteVector.view).map(ByteVector32.apply)
  def addPreimage(preimage: ByteVector32): Unit = Blocking.txWrite(Preimages.insertCompiled += (Crypto.sha256(preimage).toArray, preimage.toArray), db)
}
