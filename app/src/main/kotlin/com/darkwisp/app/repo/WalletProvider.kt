package com.darkwisp.app.repo

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class OnchainFeeQuote(
    val fastFeeSats: Long,
    val mediumFeeSats: Long,
    val slowFeeSats: Long
)

interface WalletProvider {
    val balance: StateFlow<Long?>
    val isConnected: StateFlow<Boolean>
    val statusLog: SharedFlow<String>

    /** Emits the amount in msats whenever an incoming payment is received. */
    val paymentReceived: SharedFlow<Long>

    /** Emits Unit whenever the transaction list should be refreshed. */
    val transactionsChanged: SharedFlow<Unit>

    fun hasConnection(): Boolean
    fun connect()
    fun disconnect()
    suspend fun fetchBalance(): Result<Long>
    suspend fun payInvoice(bolt11: String): Result<String>
    suspend fun makeInvoice(amountMsats: Long, description: String, expirySecs: Int = 3600): Result<String>
    suspend fun listTransactions(limit: Int = 50, offset: Int = 0): Result<List<WalletTransaction>>

    /** Spark only — returns an on-chain deposit address. NWC returns failure. */
    suspend fun getDepositAddress(): Result<String>

    /** Spark only — prepares an on-chain send and returns fee quote + opaque prepare data. */
    suspend fun prepareOnchainSend(address: String, amountSats: Long): Result<Pair<OnchainFeeQuote, Any>>

    /** Spark only — broadcasts a prepared on-chain send. */
    suspend fun sendOnchain(prepareData: Any, speed: String = "MEDIUM"): Result<String>
}

data class WalletTransaction(
    val type: String,
    val description: String?,
    val paymentHash: String,
    val amountMsats: Long,
    val feeMsats: Long = 0,
    val createdAt: Long,
    val settledAt: Long?,
    /** Pubkey of the counterparty (recipient for outgoing, sender for incoming zaps). */
    val counterpartyPubkey: String? = null,
    val pending: Boolean = false,
    val isOnchain: Boolean = false
)
