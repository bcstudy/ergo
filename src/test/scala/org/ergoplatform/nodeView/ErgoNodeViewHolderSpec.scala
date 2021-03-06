package org.ergoplatform.nodeView

import java.io.File

import org.ergoplatform.ErgoBoxCandidate
import org.ergoplatform.modifiers.ErgoFullBlock
import org.ergoplatform.modifiers.history.{ADProofs, BlockTransactions, Header}
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.nodeView.state.StateType.Utxo
import org.ergoplatform.nodeView.state._
import org.ergoplatform.settings.{Algos, ErgoSettings}
import org.ergoplatform.utils.{ErgoPropertyTest, NodeViewTestConfig, NodeViewTestOps, TestCase}
import scorex.core.NodeViewHolder.ReceivableMessages._
import scorex.core.network.NodeViewSynchronizer.ReceivableMessages._
import scorex.crypto.authds.{ADKey, SerializedAdProof}
import scorex.testkit.utils.NoShrink
import scorex.util.ModifierId

class ErgoNodeViewHolderSpec extends ErgoPropertyTest with NodeViewTestOps with NoShrink {

  private val t1 = TestCase("check genesis state") { fixture =>
    import fixture._
    getCurrentState.rootHash shouldBe getAfterGenesisStateDigest
  }

  private val t2 = TestCase("check history after genesis") { fixture =>
    import fixture._
    getBestHeaderOpt shouldBe None
  }

  private val t3 = TestCase("apply valid block header") { fixture =>
    import fixture._
    val (us, bh) = createUtxoState(Some(nodeViewHolderRef))
    val block = validFullBlock(None, us, bh)

    getBestHeaderOpt shouldBe None
    getHistoryHeight shouldBe -1

    subscribeEvents(classOf[SyntacticallySuccessfulModifier[_]])

    //sending header
    nodeViewHolderRef ! LocallyGeneratedModifier[Header](block.header)
    expectMsgType[SyntacticallySuccessfulModifier[Header]]

    getHistoryHeight shouldBe 0
    getHeightOf(block.header.id) shouldBe Some(0)
    getLastHeadersLength(10) shouldBe 1
    getOpenSurfaces shouldBe Seq(block.header.id)
    getBestHeaderOpt shouldBe Some(block.header)
  }

  private val t4 = TestCase("apply valid block as genesis") { fixture =>
    import fixture._
    val (us, bh) = createUtxoState(Some(nodeViewHolderRef))
    val genesis = validFullBlock(parentOpt = None, us, bh)

    subscribeEvents(classOf[SyntacticallySuccessfulModifier[_]])
    nodeViewHolderRef ! LocallyGeneratedModifier(genesis.header)
    expectMsgType[SyntacticallySuccessfulModifier[Header]]

    if (verifyTransactions) {
      nodeViewHolderRef ! LocallyGeneratedModifier(genesis.blockTransactions)
      expectMsgType[SyntacticallySuccessfulModifier[BlockTransactions]]
      nodeViewHolderRef ! LocallyGeneratedModifier(genesis.adProofs.get)
      expectMsgType[SyntacticallySuccessfulModifier[ADProofs]]
      nodeViewHolderRef ! LocallyGeneratedModifier(genesis.extension)
      expectMsgType[SyntacticallySuccessfulModifier[ADProofs]]
      getBestFullBlockOpt shouldBe Some(genesis)
    }
  }

  private val t5 = TestCase("apply full blocks after genesis") { fixture =>
    import fixture._
    val (us, bh) = createUtxoState(Some(nodeViewHolderRef))
    val genesis = validFullBlock(parentOpt = None, us, bh)
    val wusAfterGenesis = WrappedUtxoState(us, bh, stateConstants).applyModifier(genesis).get
    applyBlock(genesis) shouldBe 'success

    val block = validFullBlock(Some(genesis.header), wusAfterGenesis)
    applyBlock(block) shouldBe 'success
    if (verifyTransactions) {
      getBestFullBlockOpt shouldBe Some(block)
    }

    getBestHeaderOpt shouldBe Some(block.header)
    getHistoryHeight shouldBe 1
    getLastHeadersLength(10) shouldBe 2
  }

  private val t6 = TestCase("add transaction to memory pool") { fixture =>
    import fixture._
    if (stateType == Utxo) {
      val (_, bh) = createUtxoState(Some(nodeViewHolderRef))
      val tx = validTransactionsFromBoxHolder(bh)._1.head
      subscribeEvents(classOf[FailedTransaction[_]])
      nodeViewHolderRef ! LocallyGeneratedTransaction[ErgoTransaction](tx)
      expectNoMsg()
      getPoolSize shouldBe 1
    }
  }

  private val t7 = TestCase("apply invalid full block") { fixture =>
    import fixture._
    val (us, bh) = createUtxoState(Some(nodeViewHolderRef))
    val genesis = validFullBlock(parentOpt = None, us, bh)
    val wusAfterGenesis = WrappedUtxoState(us, bh, stateConstants).applyModifier(genesis).get
    // TODO looks like another bug is still present here, see https://github.com/ergoplatform/ergo/issues/309
    if (verifyTransactions) {
      applyBlock(genesis) shouldBe 'success

      val block = validFullBlock(Some(genesis.header), wusAfterGenesis)
      val wusAfterBlock = wusAfterGenesis.applyModifier(block).get

      applyBlock(block) shouldBe 'success
      getBestHeaderOpt shouldBe Some(block.header)
      if (verifyTransactions) {
        getRootHash shouldBe Algos.encode(wusAfterBlock.rootHash)
      }
      getBestHeaderOpt shouldBe Some(block.header)

      val brokenBlock = generateInvalidFullBlock(block.header, wusAfterBlock)
      applyBlock(brokenBlock) shouldBe 'success

      val brokenBlock2 = generateInvalidFullBlock(block.header, wusAfterBlock)
      brokenBlock2.header should not be brokenBlock.header
      applyBlock(brokenBlock2) shouldBe 'success

      getBestFullBlockOpt shouldBe Some(block)
      getRootHash shouldBe Algos.encode(wusAfterBlock.rootHash)
      getBestHeaderOpt shouldBe Some(block.header)
    }
  }

  private def generateInvalidFullBlock(parentHeader: Header, parentState: WrappedUtxoState) = {
    val extensionIn = extensionGen.sample.get
    val brokenBlockIn = validFullBlock(Some(parentHeader), parentState)
    val headTx = brokenBlockIn.blockTransactions.txs.head
    val wrongBoxId: ADKey = ADKey @@ Algos.hash("wrong input")
    val newInput = headTx.inputs.head.copy(boxId = wrongBoxId)
    val brokenTransactionsIn = brokenBlockIn.blockTransactions
      .copy(txs = headTx.copy(inputs = newInput +: headTx.inputs.tail) +: brokenBlockIn.blockTransactions.txs.tail)
    val brokenHeader = brokenBlockIn.header
      .copy(transactionsRoot = brokenTransactionsIn.digest, extensionRoot = extensionIn.digest)
    val brokenTransactions = brokenTransactionsIn.copy(headerId = brokenHeader.id)
    val brokenProofs = brokenBlockIn.adProofs.get.copy(headerId = brokenHeader.id)
    val extension = extensionIn.copy(headerId = brokenHeader.id)
    ErgoFullBlock(brokenHeader, brokenTransactions, extension, Some(brokenProofs))
  }

  private val t8 = TestCase("switching for a better chain") { fixture =>
    import fixture._
    val (us, bh) = createUtxoState(Some(nodeViewHolderRef))
    val genesis = validFullBlock(parentOpt = None, us, bh)
    val wusAfterGenesis = WrappedUtxoState(us, bh, stateConstants).applyModifier(genesis).get

    applyBlock(genesis) shouldBe 'success
    getRootHash shouldBe Algos.encode(wusAfterGenesis.rootHash)

    val chain1block1 = validFullBlock(Some(genesis.header), wusAfterGenesis)
    val expectedBestFullBlockOpt = if (verifyTransactions) Some(chain1block1) else None
    applyBlock(chain1block1) shouldBe 'success
    getBestFullBlockOpt shouldBe expectedBestFullBlockOpt
    getBestHeaderOpt shouldBe Some(chain1block1.header)

    val chain2block1 = validFullBlock(Some(genesis.header), wusAfterGenesis)
    applyBlock(chain2block1) shouldBe 'success
    getBestFullBlockOpt shouldBe expectedBestFullBlockOpt
    getBestHeaderOpt shouldBe Some(chain1block1.header)

    val wusChain2Block1 = wusAfterGenesis.applyModifier(chain2block1).get
    val chain2block2 = validFullBlock(Some(chain2block1.header), wusChain2Block1)
    chain2block1.header.stateRoot shouldEqual wusChain2Block1.rootHash

    applyBlock(chain2block2) shouldBe 'success
    if (verifyTransactions) {
      getBestFullBlockEncodedId shouldBe Some(chain2block2.header.encodedId)
    }

    getBestHeaderOpt shouldBe Some(chain2block2.header)
    getRootHash shouldBe Algos.encode(chain2block2.header.stateRoot)
  }

  private val t9 = TestCase("UTXO state should generate adProofs and put them in history") { fixture =>
    import fixture._
    if (stateType == StateType.Utxo) {
      val (us, bh) = createUtxoState(Some(nodeViewHolderRef))
      val genesis = validFullBlock(parentOpt = None, us, bh)

      nodeViewHolderRef ! LocallyGeneratedModifier(genesis.header)
      nodeViewHolderRef ! LocallyGeneratedModifier(genesis.blockTransactions)
      nodeViewHolderRef ! LocallyGeneratedModifier(genesis.extension)

      getBestFullBlockOpt shouldBe Some(genesis)
      getModifierById(genesis.adProofs.get.id) shouldBe genesis.adProofs
    }
  }

  private val t10 = TestCase("NodeViewHolder start from inconsistent state") { fixture =>
    import fixture._
    val (us, bh) = createUtxoState(Some(nodeViewHolderRef))
    val genesis = validFullBlock(parentOpt = None, us, bh)
    val wusAfterGenesis = WrappedUtxoState(us, bh, stateConstants).applyModifier(genesis).get
    applyBlock(genesis) shouldBe 'success

    val block1 = validFullBlock(Some(genesis.header), wusAfterGenesis)
    applyBlock(block1) shouldBe 'success
    getBestFullBlockOpt shouldBe Some(block1)
    getRootHash shouldBe Algos.encode(block1.header.stateRoot)

    stopNodeViewHolder()
    val stateDir = new File(s"${nodeViewDir.getAbsolutePath}/state")
    for (file <- stateDir.listFiles) file.delete
    startNodeViewHolder()

    getRootHash shouldBe Algos.encode(block1.header.stateRoot)
  }

  private val t11 = TestCase("apply payload in incorrect order") { fixture =>
    import fixture._
    val (us, bh) = createUtxoState(Some(nodeViewHolderRef))
    val genesis = validFullBlock(parentOpt = None, us, bh)
    val wusAfterGenesis = WrappedUtxoState(us, bh, stateConstants).applyModifier(genesis).get

    applyBlock(genesis) shouldBe 'success
    getRootHash shouldBe Algos.encode(wusAfterGenesis.rootHash)

    val chain2block1 = validFullBlock(Some(genesis.header), wusAfterGenesis)
    val wusChain2Block1 = wusAfterGenesis.applyModifier(chain2block1).get
    val chain2block2 = validFullBlock(Some(chain2block1.header), wusChain2Block1)

    subscribeEvents(classOf[SyntacticallySuccessfulModifier[Header]])
    nodeViewHolderRef ! LocallyGeneratedModifier(chain2block1.header)
    expectMsgType[SyntacticallySuccessfulModifier[Header]]

    applyBlock(chain2block2) shouldBe 'success
    getBestHeaderOpt shouldBe Some(chain2block2.header)
    getBestFullBlockEncodedId shouldBe Some(genesis.header.encodedId)

    applyPayload(chain2block1) shouldBe 'success
    getBestFullBlockEncodedId shouldBe Some(chain2block2.header.encodedId)
  }

  private val t12 = TestCase("Do not apply txs with wrong header id") { fixture =>
    import fixture._

    val (us, bh) = createUtxoState(Some(nodeViewHolderRef))
    val block = validFullBlock(None, us, bh)
    getBestHeaderOpt shouldBe None
    getHistoryHeight shouldBe -1

    subscribeEvents(classOf[SyntacticallySuccessfulModifier[_]])
    subscribeEvents(classOf[SyntacticallyFailedModification[_]])

    //sending header
    nodeViewHolderRef ! LocallyGeneratedModifier[Header](block.header)
    expectMsgType[SyntacticallySuccessfulModifier[Header]]
    getHistoryHeight shouldBe 0
    getHeightOf(block.header.id) shouldBe Some(0)

    val randomId = modifierIdGen.sample.get
    val wrongTxs1 = block.blockTransactions.copy(headerId = randomId)
    val wrongTxs2 = {
      val txs = block.blockTransactions.transactions
      val tx = txs.head
      val wrongOutputs = tx.outputCandidates.map(o =>
        new ErgoBoxCandidate(o.value + 10L, o.proposition, o.additionalTokens, o.additionalRegisters)
      )
      val wrongTxs = tx.copy(outputCandidates = wrongOutputs) +: txs.tail
      block.blockTransactions.copy(txs = wrongTxs)
    }
    val wrongTxs3 = {
      val txs = block.blockTransactions.transactions
      val tx = txs.head
      val wrongInputs = tx.inputs.map { input =>
        input.copy(boxId = ADKey @@ input.boxId.reverse)
      }
      val wrongTxs = tx.copy(inputs = wrongInputs) +: txs.tail
      block.blockTransactions.copy(txs = wrongTxs)
    }

    nodeViewHolderRef ! LocallyGeneratedModifier[BlockTransactions](wrongTxs1)
    expectMsgType[SyntacticallyFailedModification[BlockTransactions]]

    nodeViewHolderRef ! LocallyGeneratedModifier[BlockTransactions](wrongTxs2)
    expectMsgType[SyntacticallyFailedModification[BlockTransactions]]

    nodeViewHolderRef ! LocallyGeneratedModifier[BlockTransactions](wrongTxs3)
    expectMsgType[SyntacticallyFailedModification[BlockTransactions]]

    nodeViewHolderRef ! LocallyGeneratedModifier[BlockTransactions](block.blockTransactions)
    expectMsgType[SyntacticallySuccessfulModifier[BlockTransactions]]
  }

  private val t13 = TestCase("Do not apply wrong adProofs") { fixture =>
    import fixture._

    val (us, bh) = createUtxoState(Some(nodeViewHolderRef))
    val block = validFullBlock(None, us, bh)
    getBestHeaderOpt shouldBe None

    getHistoryHeight shouldBe -1

    subscribeEvents(classOf[SyntacticallySuccessfulModifier[_]])
    subscribeEvents(classOf[SyntacticallyFailedModification[_]])

    //sending header
    nodeViewHolderRef ! LocallyGeneratedModifier[Header](block.header)
    expectMsgType[SyntacticallySuccessfulModifier[Header]]

    val randomId = modifierIdGen.sample.get
    val wrongProofsBytes = SerializedAdProof @@ block.adProofs.get.proofBytes.reverse
    val wrongProofs1 = block.adProofs.map(_.copy(headerId = randomId))
    val wrongProofs2 = block.adProofs.map(_.copy(proofBytes = wrongProofsBytes))

    nodeViewHolderRef ! LocallyGeneratedModifier[ADProofs](wrongProofs1.get)
    expectMsgType[SyntacticallyFailedModification[ADProofs]]
    nodeViewHolderRef ! LocallyGeneratedModifier[ADProofs](wrongProofs2.get)
    expectMsgType[SyntacticallyFailedModification[ADProofs]]

    nodeViewHolderRef ! LocallyGeneratedModifier[ADProofs](block.adProofs.get)
    expectMsgType[SyntacticallySuccessfulModifier[ADProofs]]
  }

  private val t14 = TestCase("do not apply genesis block header if " +
                             "it's not equal to genesisId from config") { fixture =>
    import fixture._
    updateConfig(genesisIdConfig(modifierIdGen.sample))
    val (us, bh) = createUtxoState(Some(nodeViewHolderRef))
    val block = validFullBlock(None, us, bh)

    getBestHeaderOpt shouldBe None
    getHistoryHeight shouldBe -1

    subscribeEvents(classOf[SyntacticallySuccessfulModifier[_]])
    subscribeEvents(classOf[SyntacticallyFailedModification[_]])

    //sending header
    nodeViewHolderRef ! LocallyGeneratedModifier[Header](block.header)
    expectMsgType[SyntacticallyFailedModification[Header]]
    getBestHeaderOpt shouldBe None
    getHistoryHeight shouldBe -1
  }

  private val t15 = TestCase("apply genesis block header if it's equal to genesisId from config") { fixture =>
    import fixture._
    val (us, bh) = createUtxoState(Some(nodeViewHolderRef))
    val block = validFullBlock(None, us, bh)
    updateConfig(genesisIdConfig(Some(ModifierId @@ Algos.encode(block.header.id))))

    getBestHeaderOpt shouldBe None
    getHistoryHeight shouldBe -1

    subscribeEvents(classOf[SyntacticallySuccessfulModifier[_]])
    subscribeEvents(classOf[SyntacticallyFailedModification[_]])

    nodeViewHolderRef ! LocallyGeneratedModifier[Header](block.header)
    expectMsgType[SyntacticallySuccessfulModifier[Header]]
    getHistoryHeight shouldBe 0
    getHeightOf(block.header.id) shouldBe Some(0)
  }

  val cases: List[TestCase] = List(t1, t2, t3, t4, t5, t6, /*t7,*/ t8, t9)
  NodeViewTestConfig.allConfigs.foreach { c =>
    cases.foreach { t =>
      property(s"${t.name} - $c") {
        t.run(c)
      }
    }
  }

  val verifyingTxCases = List(t10, t11, t12, t13)

  NodeViewTestConfig.verifyTxConfigs.foreach { c =>
    verifyingTxCases.foreach { t =>
      property(s"${t.name} - $c") {
        t.run(c)
      }
    }
  }

  val genesisIdTestCases = List(t14, t15)
  def genesisIdConfig(expectedGenesisIdOpt: Option[ModifierId])(protoSettings: ErgoSettings): ErgoSettings = {
    protoSettings.copy(chainSettings = protoSettings.chainSettings.copy(genesisId = expectedGenesisIdOpt))
  }

  genesisIdTestCases.foreach { t =>
    property(t.name) {
      t.run(NodeViewTestConfig(StateType.Digest, verifyTransactions = true, popowBootstrap = true))
    }
  }
}
