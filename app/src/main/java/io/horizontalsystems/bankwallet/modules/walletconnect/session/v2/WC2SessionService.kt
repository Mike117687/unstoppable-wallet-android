package io.horizontalsystems.bankwallet.modules.walletconnect.session.v2

import android.util.Log
import com.walletconnect.walletconnectv2.client.WalletConnect
import io.horizontalsystems.bankwallet.core.IAccountManager
import io.horizontalsystems.bankwallet.core.managers.ConnectivityManager
import io.horizontalsystems.bankwallet.modules.walletconnect.WalletConnectModule
import io.horizontalsystems.bankwallet.modules.walletconnect.session.v1.WCSessionModule.PeerMetaItem
import io.horizontalsystems.bankwallet.modules.walletconnect.version2.*
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject

class WC2SessionService(
    private val service: WC2Service,
    private val wcManager: WC2Manager,
    private val sessionManager: WC2SessionManager,
    private val accountManager: IAccountManager,
    private val pingService: WC2PingService,
    private val connectivityManager: ConnectivityManager,
    private val topic: String?,
    private val connectionLink: String?
) {

    private val TAG = "WC2SessionService"

    sealed class State {
        object Idle : State()
        class Invalid(val error: Throwable) : State()
        object WaitingForApproveSession : State()
        object Ready : State()
        object Killed : State()
    }

    var proposal: WalletConnect.Model.SessionProposal? = null
        private set

    var session: WalletConnect.Model.SettledSession? = null
        private set

    private val stateSubject = PublishSubject.create<State>()
    val stateObservable: Flowable<State>
        get() = stateSubject.toFlowable(BackpressureStrategy.BUFFER)

    private val pendingRequestSubject = PublishSubject.create<WalletConnect.Model.SessionRequest>()
    val pendingRequestObservable: Flowable<WalletConnect.Model.SessionRequest>
        get() = pendingRequestSubject.toFlowable(BackpressureStrategy.BUFFER)

    var state: State = State.Idle
        private set(value) {
            field = value
            stateSubject.onNext(value)
        }

    val appMetaItem: PeerMetaItem?
        get() {
            session?.peerAppMetaData?.let {
                return PeerMetaItem(
                    it.name,
                    it.url,
                    it.description,
                    it.icons.last()
                )
            }
            proposal?.let {
                return PeerMetaItem(
                    it.name,
                    it.url,
                    it.description,
                    it.icons.lastOrNull()?.toString()
                )
            }
            return null
        }

    val connectionState by pingService::state
    val connectionStateObservable by pingService::stateObservable

    private val disposables = CompositeDisposable()

    fun start() {
        connectionLink?.let {
            service.pair(it)
        }

        topic?.let { topic ->
            val existingSession =
                sessionManager.sessions.firstOrNull { it.topic == topic } ?: return@let
            session = existingSession
            state = State.Ready
            pingService.ping(existingSession.topic)
        }

        connectivityManager.networkAvailabilitySignal
            .subscribeOn(Schedulers.io())
            .subscribe {
                if (!connectivityManager.isConnected) {
                    pingService.disconnect()
                } else {
                    session?.let {
                        pingService.ping(it.topic)
                    }
                }
            }
            .let {
                disposables.add(it)
            }

        service.eventObservable
            .subscribe { event ->
                when (event) {
                    is WC2Service.Event.WaitingForApproveSession -> {
                        proposal = event.proposal
                        state = State.WaitingForApproveSession
                        pingService.receiveResponse()
                    }
                    is WC2Service.Event.SessionDeleted -> {
                        session?.topic?.let { topic ->
                            if (topic == event.deletedSession.topic) {
                                state = State.Killed
                                pingService.disconnect()
                            }
                        }
                    }
                    is WC2Service.Event.SessionSettled -> {
                        session = event.session
                        state = State.Ready
                        pingService.receiveResponse()
                    }
                    is WC2Service.Event.Error -> {
                        state = State.Invalid(event.error)
                    }
                    is WC2Service.Event.SessionRequest -> {
                        pendingRequestSubject.onNext(event.sessionRequest)
                        pingService.receiveResponse()
                    }
                }
            }
            .let {
                disposables.add(it)
            }
    }

    fun stop() {
        disposables.clear()
    }

    fun reject() {
        proposal?.let {
            service.reject(it)
            pingService.disconnect()
            state = State.Killed
        }
    }

    fun approve() {
        val proposal = proposal ?: return
        val account = accountManager.activeAccount ?: run {
            state = State.Invalid(WalletConnectModule.NoSuitableAccount)
            return
        }

        val chainIds = proposal.chains.mapNotNull { WC2Parser.getChainId(it) }

        val wrappersMap = chainIds.mapNotNull { chainId ->
            wcManager.evmKitWrapper(chainId, account)?.let {
                chainId to it
            }
        }.toMap()

        if (wrappersMap.isEmpty()) {
            state = State.Invalid(WalletConnectModule.UnsupportedChainId)
            return
        }

        val accounts: List<String> = chainIds.mapNotNull { chainId ->
            val wrapper = wrappersMap[chainId] ?: return@mapNotNull null
            "eip155:$chainId:${wrapper.evmKit.receiveAddress.eip55}"
        }

        Log.e(TAG, "approve: accounts: $accounts")
        service.approve(proposal, accounts)
    }


    fun disconnect() {
        val sessionNonNull = session ?: return

        state = State.Killed
        service.disconnect(sessionNonNull.topic)
        pingService.disconnect()
    }

    fun reconnect() {
        session?.let {
            pingService.ping(it.topic)
        }
    }
}
