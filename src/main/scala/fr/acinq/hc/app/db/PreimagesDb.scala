package fr.acinq.hc.app.db

import fr.acinq.bitcoin.ByteVector32
import scodec.bits.ByteVector
import slick.jdbc.PostgresProfile
import slick.jdbc.PostgresProfile.api._


class PreimagesDb(val db: PostgresProfile.backend.Database) {
  def findByHash(hash: ByteVector32): Option[ByteVector32] = Blocking.txRead(Preimages.findByHash(hash.toArray).result.headOption, db).map(ByteVector.view).map(ByteVector32.apply)
  def addPreimage(hash: ByteVector32, preimage: ByteVector32): Boolean = Blocking.txWrite(Preimages.insertCompiled += (hash.toArray, preimage.toArray), db) > 0
}
