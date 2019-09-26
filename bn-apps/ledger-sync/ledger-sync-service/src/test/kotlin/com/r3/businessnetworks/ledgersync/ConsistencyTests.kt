package com.r3.businessnetworks.ledgersync

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByService
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.MAX_PAGE_SIZE
import net.corda.core.node.services.vault.PageSpecification
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.utilities.getOrThrow
import net.corda.testing.node.MockNetworkNotarySpec
import net.corda.testing.node.internal.InternalMockNetwork
import net.corda.testing.node.internal.InternalMockNodeParameters
import net.corda.testing.node.internal.TestStartedNode
import net.corda.testing.node.internal.startFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

abstract class ConsistencyTests {
    abstract val cordappPackages: List<String>

    private val notary = CordaX500Name("Notary", "London", "GB")

    internal val node1 = CordaX500Name("Member 1", "Paris", "FR")
    internal val node2 = CordaX500Name("Member 2", "Paris", "FR")
    internal val node3 = CordaX500Name("Member 3", "Paris", "FR")

    protected lateinit var mockNetwork: InternalMockNetwork

    @Before
    fun start() {
        mockNetwork = InternalMockNetwork(
                cordappPackages = cordappPackages,
                notarySpecs = listOf(MockNetworkNotarySpec(notary))
        )

        mockNetwork.createNode(InternalMockNodeParameters(legalName = node1))
        mockNetwork.createNode(InternalMockNodeParameters(legalName = node2))
        mockNetwork.createNode(InternalMockNodeParameters(legalName = node3))

        mockNetwork.runNetwork()
    }

    @After
    fun stop() {
        mockNetwork.stopNodes()
    }

    @Test
    fun `members receive no ids to sync if they hold all transactions the counterparty is aware of`() {
        assertEquals(0, node1.fromNetwork().bogusStateCount())

        node1.fromNetwork().createTransactions()

        assertEquals(2, node1.fromNetwork().bogusStateCount())

        val missingTransactions = node1.fromNetwork().runRequestLedgerSyncFlow(node1.fromNetwork().regularNodes())

        assertEquals(mapOf(
                node2.fromNetwork().identity() to LedgerSyncFindings(emptyList(), emptyList()),
                node3.fromNetwork().identity() to LedgerSyncFindings(emptyList(), emptyList())
        ), missingTransactions)
    }

    @Test
    fun `reports member ids to sync missing from requester`() {
        node1.fromNetwork().createTransactions()
        assertEquals(2, node1.fromNetwork().bogusStateCount())
        node1.fromNetwork().simulateCatastrophicFailure()
        assertEquals(0, node1.fromNetwork().bogusStateCount())
        node1.fromNetwork().runRequestLedgerSyncFlow(node1.fromNetwork().regularNodes())

        val missingTransactions = node1.fromNetwork().runRequestLedgerSyncFlow(node1.fromNetwork().regularNodes())

        assertEquals(1, missingTransactions[node2.fromNetwork().identity()]!!.missingAtRequester.size)
        assertEquals(1, missingTransactions[node3.fromNetwork().identity()]!!.missingAtRequester.size)
    }

    @Test
    fun `reports member ids to sync from requestee`() {
        node1.fromNetwork().createTransactions()
        node2.fromNetwork().simulateCatastrophicFailure()
        assertEquals(0, node2.fromNetwork().bogusStateCount())

        node3.fromNetwork().simulateCatastrophicFailure()
        assertEquals(0, node3.fromNetwork().bogusStateCount())

        node1.fromNetwork().runRequestLedgerSyncFlow(node1.fromNetwork().regularNodes())

        val missingTransactions = node1.fromNetwork().runRequestLedgerSyncFlow(node1.fromNetwork().regularNodes())

        assertEquals(1, missingTransactions[node2.fromNetwork().identity()]!!.missingAtRequestee.size)
        assertEquals(0, missingTransactions[node2.fromNetwork().identity()]!!.missingAtRequester.size)
        assertEquals(1, missingTransactions[node3.fromNetwork().identity()]!!.missingAtRequestee.size)
        assertEquals(0, missingTransactions[node3.fromNetwork().identity()]!!.missingAtRequester.size)
    }

    @Test
    fun `ledger consistency is reported for consistent ledgers`() {
        node1.fromNetwork().createTransactions()
        val actual = node1.fromNetwork().runEvaluateLedgerConsistencyFlow(node1.fromNetwork().regularNodes())
        assertEquals(mapOf(
                node2.fromNetwork().identity() to true,
                node3.fromNetwork().identity() to true
        ), actual)
    }

    @Test
    fun `ledger consistency is reported for inconsistent ledger`() {
        node1.fromNetwork().createTransactions()
        node1.fromNetwork().simulateCatastrophicFailure()
        val actual = node1.fromNetwork().runEvaluateLedgerConsistencyFlow(node1.fromNetwork().regularNodes())
        assertEquals(mapOf(
                node2.fromNetwork().identity() to false,
                node3.fromNetwork().identity() to false
        ), actual)
    }

    @Test
    fun `ledger consistency is reported for inconsistent counterparties`() {
        node1.fromNetwork().createTransactions()
        node3.fromNetwork().simulateCatastrophicFailure()
        val actual = node1.fromNetwork().runEvaluateLedgerConsistencyFlow(node1.fromNetwork().regularNodes())
        assertEquals(mapOf(
                node2.fromNetwork().identity() to true,
                node3.fromNetwork().identity() to false
        ), actual)
    }

    @Test
    fun `transactions can be recovered`() {
        node1.fromNetwork().createTransactions(3)

        assertEquals(6, node1.fromNetwork().bogusStateCount())

        node1.fromNetwork().simulateCatastrophicFailure()

        val consistencyResult = node1.fromNetwork().runEvaluateLedgerConsistencyFlow(node1.fromNetwork().regularNodes())

        assertEquals(mapOf(
                node2.fromNetwork().identity() to false,
                node3.fromNetwork().identity() to false
        ), consistencyResult)

        assertEquals(0, node1.fromNetwork().bogusStateCount())

        val ledgerSyncResult = node1.fromNetwork().runRequestLedgerSyncFlow(node1.fromNetwork().regularNodes())

        assertEquals(3, ledgerSyncResult[node2.fromNetwork().identity()]!!.missingAtRequester.size)
        assertEquals(3, ledgerSyncResult[node3.fromNetwork().identity()]!!.missingAtRequester.size)

        node1.fromNetwork().runTransactionRecoveryFlow(ledgerSyncResult)

        assertEquals(6, node1.fromNetwork().bogusStateCount())
    }

    @Test
    fun `transactions are recovered by LedgerSyncAndRecoveryFlow from every node when members list is empty`() {
        node1.fromNetwork().createTransactions(3)

        assertEquals(6, node1.fromNetwork().bogusStateCount())

        node1.fromNetwork().simulateCatastrophicFailure()

        assertEquals(0, node1.fromNetwork().bogusStateCount())

        node1.fromNetwork().runLedgerSyncAndRecoveryFlow(null)

        assertEquals(6, node1.fromNetwork().bogusStateCount())
    }

    @Test
    fun `transactions are recovered by LedgerSyncAndRecoveryFlow when passing only Bank2 node in members list`() {
        node1.fromNetwork().createTransactions(3)

        assertEquals(6, node1.fromNetwork().bogusStateCount())

        node1.fromNetwork().simulateCatastrophicFailure()

        assertEquals(0, node1.fromNetwork().bogusStateCount())

        node1.fromNetwork().runLedgerSyncAndRecoveryFlow(listOf(node2.fromNetwork().identity()))

        // We only expect 3 states to be recovered from Bank2
        assertEquals(3, node1.fromNetwork().bogusStateCount())
    }

    /**
     * All Helper Functions that are used by both test here
     */
    fun TestStartedNode.runRequestLedgerSyncFlow(members: List<Party>): Map<Party, LedgerSyncFindings> {
        val future = services.startFlow(RequestLedgersSyncFlow(members)).resultFuture
        mockNetwork.runNetwork()
        return future.getOrThrow()
    }

    fun TestStartedNode.runTransactionRecoveryFlow(report: Map<Party, LedgerSyncFindings>) {
        val future = services.startFlow(TransactionRecoveryFlow(report)).resultFuture
        mockNetwork.runNetwork()
        future.getOrThrow()
    }

    private fun TestStartedNode.runEvaluateLedgerConsistencyFlow(members: List<Party>): Map<Party, Boolean> {
        val future = services.startFlow(EvaluateLedgerConsistencyFlow(members)).resultFuture
        mockNetwork.runNetwork()
        return future.getOrThrow()
    }

    private fun TestStartedNode.runLedgerSyncAndRecoveryFlow(members: List<Party>?) {
        val future = services.startFlow(LedgerSyncAndRecoveryFlow(members)).resultFuture
        mockNetwork.runNetwork()
        future.getOrThrow()
    }

    fun TestStartedNode.regularNodes(): List<Party> = listOf(node1, node2, node3).map {
        services.identityService.wellKnownPartyFromX500Name(it)!!
    }

    fun TestStartedNode.identity() = info.legalIdentities.first()

    /*
     * The number of states in this node's vault
     */
    fun TestStartedNode.bogusStateCount() = database.transaction {
        services.vaultService.queryBy(
                BogusState::class.java,
                QueryCriteria.VaultQueryCriteria(Vault.StateStatus.ALL),
                PageSpecification(1, MAX_PAGE_SIZE)
        )
    }.totalStatesAvailable.toInt()

    fun TestStartedNode.simulateCatastrophicFailure() {
        services.database.transaction {
            connection.prepareStatement("""SELECT transaction_id FROM VAULT_STATES WHERE CONTRACT_STATE_CLASS_NAME='${BogusState::class.java.canonicalName}'""").executeQuery().let { results ->
                while (results.next()) {
                    results.getString(1).let { transactionId ->
                        connection.prepareStatement("""DELETE FROM NODE_TRANSACTIONS WHERE tx_id='$transactionId'""").execute()
                        connection.prepareStatement("""DELETE FROM VAULT_LINEAR_STATES_PARTS WHERE transaction_id='$transactionId'""").execute()
                        connection.prepareStatement("""DELETE FROM VAULT_LINEAR_STATES WHERE transaction_id='$transactionId'""").execute()
                        connection.prepareStatement("""DELETE FROM VAULT_STATES WHERE transaction_id='$transactionId'""").execute()
                    }
                }
            }
        }

        restart()

    }

    fun CordaX500Name.fromNetwork(): TestStartedNode = mockNetwork.nodes.lastOrNull {
        it.configuration.myLegalName == this
    }?.started!!

    fun TestStartedNode.createTransactions(count: Int = 1) {
        (regularNodes() - identity()).forEach { party ->
            createTransaction(party, count)
        }
    }

    fun TestStartedNode.createTransaction(counterParty: Party, count: Int = 1) {
        repeat(count) {
            val future = services.startFlow(BogusFlow(counterParty)).resultFuture
            mockNetwork.runNetwork()
            future.getOrThrow()
        }
    }

    fun TestStartedNode.restart() {
        internals.disableDBCloseOnStop()
        internals.stop()
        mockNetwork.createNode(
                InternalMockNodeParameters(legalName = internals.configuration.myLegalName, forcedID = internals.id)
        )

    }

    @StartableByService
    class VaultRecyclerExistFlow : FlowLogic<Boolean>() {
        @Suspendable
        override fun call() = serviceHub.vrExist()
    }
}