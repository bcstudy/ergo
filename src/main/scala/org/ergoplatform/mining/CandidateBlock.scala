package org.ergoplatform.mining

import io.circe.Encoder
import io.circe.syntax._
import org.ergoplatform.modifiers.history.{Extension, ExtensionCandidate, Header}
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.settings.Algos
import scorex.core.block.Block.Timestamp
import scorex.crypto.authds.{ADDigest, SerializedAdProof}

case class CandidateBlock(parentOpt: Option[Header],
                          nBits: Long,
                          stateRoot: ADDigest,
                          adProofBytes: SerializedAdProof,
                          transactions: Seq[ErgoTransaction],
                          timestamp: Timestamp,
                          extension: ExtensionCandidate) {

  override def toString: String = s"CandidateBlock(${this.asJson})"
}

object CandidateBlock {
  implicit val jsonEncoder: Encoder[CandidateBlock] = (c: CandidateBlock) =>
    Map(
      "parentId" -> c.parentOpt.map(p => Algos.encode(p.id)).getOrElse("None").asJson,
      "nBits" -> c.nBits.asJson,
      "stateRoot" -> Algos.encode(c.stateRoot).asJson,
      "adProofBytes" -> Algos.encode(c.adProofBytes).asJson,
      "timestamp" -> c.timestamp.asJson,
      "transactions" -> c.transactions.map(_.asJson).asJson,
      "transactionsNumber" -> c.transactions.length.asJson,
      "extensionHash" -> Algos.encode(Extension.rootHash(c.extension)).asJson
    ).asJson

}
