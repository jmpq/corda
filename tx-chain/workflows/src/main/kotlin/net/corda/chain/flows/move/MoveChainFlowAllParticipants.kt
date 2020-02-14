package net.corda.chain.flows.move

import co.paralleluniverse.fibers.Suspendable
import net.corda.chain.contracts.ChainContractWithChecks
import net.corda.chain.flows.chainsnipping.ConsumeTxFlow
import net.corda.chain.flows.chainsnipping.ReIssueTxFlow
import net.corda.chain.states.ChainStateAllParticipants
import net.corda.core.contracts.Command
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker


/**
 * Some Flow
 *
 */

@StartableByService
@InitiatingFlow
@StartableByRPC
class MoveChainFlowAllParticipantsFlow (
        private val linearId: UniqueIdentifier,
        private val partyA: Party,
        private val partyB: Party
) : FlowLogic<SignedTransaction>()  {

    override val progressTracker: ProgressTracker = tracker()

    companion object {
        object START : ProgressTracker.Step("Starting")
        object END : ProgressTracker.Step("Ending") {
            override fun childProgressTracker() = FinalityFlow.tracker()
        }

        fun tracker() = ProgressTracker(START, END)
    }

    @Suspendable
    override fun call(): SignedTransaction {

        // get previous state
        val linearStateQueryCriteria=
                QueryCriteria.LinearStateQueryCriteria(linearId = listOf(linearId))

        val criteria = QueryCriteria.VaultQueryCriteria(
                status = Vault.StateStatus.UNCONSUMED
        )

        val chainStateAndRef = serviceHub.vaultService.
                queryBy(criteria = criteria.and(linearStateQueryCriteria),
                        contractStateType = ChainStateAllParticipants::class.java)
                .states.single()

        // Get Chain
        var lastTxHash = chainStateAndRef.ref.txhash
        var i = 1
        while (lastTxHash != null) {
            val tx = serviceHub.validatedTransactions.getTransaction(lastTxHash)
                    ?: throw FlowException("Tx with hash $lastTxHash")

            if (tx.inputs.isEmpty()) { // reached the beginning
                break
            }
            else {
                lastTxHash = tx.inputs.single().txhash
                i++
            }
        }

        println("Tx Chain length is $i ")

        // Consume old transaction
        // Consume state
        val consumeTx= subFlow(ConsumeTxFlow (currentState = chainStateAndRef,
                partyA = partyA, partyB = partyB))

        val reIssueTx = subFlow(ReIssueTxFlow (currentState = chainStateAndRef,
                partyA = partyA, partyB = partyB))


        // Create New Transaction
        //val chainState = chainStateAndRef.state.data

        val reIssuedState =
                reIssueTx.coreTransaction.outputsOfType<ChainStateAllParticipants>().single()

        // Create Transaction
        val notary = serviceHub.networkMapCache.notaryIdentities.single()

        // list of signers
        val command =
                Command(ChainContractWithChecks.Commands.Move(),
                        listOf(partyA.owningKey, partyB.owningKey, ourIdentity.owningKey))

        //val state = chainState.copy()

        // with input state with command
        val utx = TransactionBuilder(notary = notary)
                .addOutputState(reIssuedState, ChainContractWithChecks.ID)
                .addCommand(command)

        val ptx = serviceHub.signInitialTransaction(utx,
                listOf(ourIdentity.owningKey)
        )

        val sessionA = initiateFlow(partyA)
        val sessionB = initiateFlow(partyB)

        val stx = subFlow(CollectSignaturesFlow(ptx, listOf(sessionA, sessionB)))

        // sessions with the non-local participants
        return subFlow(FinalityFlow(stx, listOf(sessionA, sessionB), END.childProgressTracker()))

    }
}


@InitiatedBy(MoveChainFlowAllParticipantsFlow::class)
class MoveChainFlowAllParticipantsResponder(
        private val counterpartySession: FlowSession
) : FlowLogic <Unit> () {

    @Suspendable
    override fun call() {

        val transactionSigner: SignTransactionFlow
        transactionSigner = object : SignTransactionFlow(counterpartySession) {
            @Suspendable override fun checkTransaction(stx: SignedTransaction) = requireThat {
//                Example:
//                val tx = stx.tx
//                val commands = tx.commands
//                "There must be exactly one command" using (commands.size == 1)
            }
        }
        val transaction= subFlow(transactionSigner)
        val expectedId = transaction.id
        val txRecorded = subFlow(ReceiveFinalityFlow(counterpartySession, expectedId))
    }
}



