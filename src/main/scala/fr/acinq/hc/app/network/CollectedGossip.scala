package fr.acinq.hc.app.network

import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate}
import fr.acinq.eclair.router.Announcements
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.ShortChannelId


case class AnnouncementSeenFrom(seenFrom: Set[PublicKey], announcement: ChannelAnnouncement) {
  def tuple: (ShortChannelId, AnnouncementSeenFrom) = (announcement.shortChannelId, this)
}

case class UpdateSeenFrom(seenFrom: Set[PublicKey], update: ChannelUpdate) {
  def tuple: (ShortChannelId, UpdateSeenFrom) = (update.shortChannelId, this)
}

case class CollectedGossip(announces: Map[ShortChannelId, AnnouncementSeenFrom],
                           updates1: Map[ShortChannelId, UpdateSeenFrom] = Map.empty,
                           updates2: Map[ShortChannelId, UpdateSeenFrom] = Map.empty) {

  def addAnnounce(announce: ChannelAnnouncement, from: PublicKey): CollectedGossip = announces.get(announce.shortChannelId) match {
    case Some(announceSeenFrom) => copy(announces = announces + AnnouncementSeenFrom(announceSeenFrom.seenFrom + from, announce).tuple)
    case None => copy(announces = announces + AnnouncementSeenFrom(seenFrom = Set(from), announce).tuple)
  }

  def addUpdate(update: ChannelUpdate, from: PublicKey): CollectedGossip =
    if (Announcements isNode1 update.channelFlags) addUpdate1(update, from)
    else addUpdate2(update, from)

  private def addUpdate1(update: ChannelUpdate, from: PublicKey): CollectedGossip = updates1.get(update.shortChannelId) match {
    case Some(updateSeenFrom) => copy(updates1 = updates1 + UpdateSeenFrom(updateSeenFrom.seenFrom + from, update).tuple)
    case None => copy(updates1 = updates1 + UpdateSeenFrom(seenFrom = Set(from), update).tuple)
  }

  private def addUpdate2(update: ChannelUpdate, from: PublicKey): CollectedGossip = updates2.get(update.shortChannelId) match {
    case Some(updateSeenFrom) => copy(updates2 = updates2 + UpdateSeenFrom(updateSeenFrom.seenFrom + from, update).tuple)
    case None => copy(updates2 = updates2 + UpdateSeenFrom(seenFrom = Set(from), update).tuple)
  }

  def asString = s"announces=${announces.size}, updates1=${updates1.size}, updates2=${updates2.size}"
}