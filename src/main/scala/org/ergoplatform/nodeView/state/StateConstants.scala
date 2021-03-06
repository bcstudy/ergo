package org.ergoplatform.nodeView.state

import akka.actor.ActorRef
import org.ergoplatform.ErgoBox
import org.ergoplatform.mining.emission.EmissionRules

/**
  * Constants, that do not change with state version changes
  *
  * @param nodeViewHolderRef - actor ref of node view holder
  * @param emission          - emission curve
  */
case class StateConstants(nodeViewHolderRef: Option[ActorRef], emission: EmissionRules, keepVersions: Int) {
  lazy val genesisEmissionBox: ErgoBox = ErgoState.genesisEmissionBox(emission)
}
