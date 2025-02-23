package io.horizontalsystems.bankwallet.core

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.walletconnect.walletconnectv2.client.WalletConnect
import com.walletconnect.walletconnectv2.client.WalletConnectClient
import io.horizontalsystems.bankwallet.BuildConfig
import io.horizontalsystems.bankwallet.core.factories.AccountFactory
import io.horizontalsystems.bankwallet.core.factories.AdapterFactory
import io.horizontalsystems.bankwallet.core.factories.AddressParserFactory
import io.horizontalsystems.bankwallet.core.managers.*
import io.horizontalsystems.bankwallet.core.providers.AppConfigProvider
import io.horizontalsystems.bankwallet.core.providers.FeeCoinProvider
import io.horizontalsystems.bankwallet.core.providers.FeeRateProvider
import io.horizontalsystems.bankwallet.core.storage.*
import io.horizontalsystems.bankwallet.modules.enablecoins.EnableCoinsEip20Provider
import io.horizontalsystems.bankwallet.modules.hsnft.HsNftApiProvider
import io.horizontalsystems.bankwallet.modules.keystore.KeyStoreActivity
import io.horizontalsystems.bankwallet.modules.launcher.LauncherActivity
import io.horizontalsystems.bankwallet.modules.lockscreen.LockScreenActivity
import io.horizontalsystems.bankwallet.modules.nft.NftManager
import io.horizontalsystems.bankwallet.modules.settings.theme.ThemeType
import io.horizontalsystems.bankwallet.modules.tor.TorConnectionActivity
import io.horizontalsystems.bankwallet.modules.walletconnect.storage.WC1SessionStorage
import io.horizontalsystems.bankwallet.modules.walletconnect.storage.WC2SessionStorage
import io.horizontalsystems.bankwallet.modules.walletconnect.version1.WC1Manager
import io.horizontalsystems.bankwallet.modules.walletconnect.version1.WC1RequestManager
import io.horizontalsystems.bankwallet.modules.walletconnect.version1.WC1SessionManager
import io.horizontalsystems.bankwallet.modules.walletconnect.version2.WC2Manager
import io.horizontalsystems.bankwallet.modules.walletconnect.version2.WC2Service
import io.horizontalsystems.bankwallet.modules.walletconnect.version2.WC2SessionManager
import io.horizontalsystems.core.BackgroundManager
import io.horizontalsystems.core.CoreApp
import io.horizontalsystems.core.ICoreApp
import io.horizontalsystems.core.security.EncryptionManager
import io.horizontalsystems.core.security.KeyStoreManager
import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.marketkit.MarketKit
import io.horizontalsystems.pin.PinComponent
import io.reactivex.plugins.RxJavaPlugins
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.system.exitProcess
import androidx.work.Configuration as WorkConfiguration

class App : CoreApp(), WorkConfiguration.Provider  {

    companion object : ICoreApp by CoreApp {

        lateinit var feeRateProvider: FeeRateProvider
        lateinit var localStorage: ILocalStorage
        lateinit var marketStorage: IMarketStorage
        lateinit var torKitManager: ITorManager
        lateinit var chartTypeStorage: IChartTypeStorage
        lateinit var restoreSettingsStorage: IRestoreSettingsStorage

        lateinit var wordsManager: WordsManager
        lateinit var networkManager: INetworkManager
        lateinit var backgroundStateChangeListener: BackgroundStateChangeListener
        lateinit var appConfigProvider: AppConfigProvider
        lateinit var adapterManager: IAdapterManager
        lateinit var transactionAdapterManager: TransactionAdapterManager
        lateinit var walletManager: IWalletManager
        lateinit var walletActivator: WalletActivator
        lateinit var walletStorage: IWalletStorage
        lateinit var accountManager: IAccountManager
        lateinit var accountFactory: IAccountFactory
        lateinit var backupManager: IBackupManager

        lateinit var connectivityManager: ConnectivityManager
        lateinit var appDatabase: AppDatabase
        lateinit var accountsStorage: IAccountsStorage
        lateinit var enabledWalletsStorage: IEnabledWalletStorage
        lateinit var blockchainSettingsStorage: IBlockchainSettingsStorage
        lateinit var ethereumKitManager: EvmKitManager
        lateinit var binanceSmartChainKitManager: EvmKitManager
        lateinit var binanceKitManager: BinanceKitManager
        lateinit var numberFormatter: IAppNumberFormatter
        lateinit var addressParserFactory: AddressParserFactory
        lateinit var feeCoinProvider: FeeCoinProvider
        lateinit var initialSyncModeSettingsManager: IInitialSyncModeSettingsManager
        lateinit var accountCleaner: IAccountCleaner
        lateinit var rateAppManager: IRateAppManager
        lateinit var coinManager: ICoinManager
        lateinit var wc1SessionStorage: WC1SessionStorage
        lateinit var wc1SessionManager: WC1SessionManager
        lateinit var wc1RequestManager: WC1RequestManager
        lateinit var wc2Service: WC2Service
        lateinit var wc2SessionManager: WC2SessionManager
        lateinit var wc1Manager: WC1Manager
        lateinit var wc2Manager: WC2Manager
        lateinit var termsManager: ITermsManager
        lateinit var marketFavoritesManager: MarketFavoritesManager
        lateinit var marketKit: MarketKit
        lateinit var activateCoinManager: ActivateCoinManager
        lateinit var releaseNotesManager: ReleaseNotesManager
        lateinit var restoreSettingsManager: RestoreSettingsManager
        lateinit var evmNetworkManager: EvmNetworkManager
        lateinit var accountSettingManager: AccountSettingManager
        lateinit var nftManager: NftManager
    }

    override val testMode = BuildConfig.testMode

    override fun onCreate() {
        super.onCreate()

        if (!BuildConfig.DEBUG) {
            //Disable logging for lower levels in Release build
            Logger.getLogger("").level = Level.SEVERE
        }

        RxJavaPlugins.setErrorHandler { e: Throwable? ->
            Log.w("RxJava ErrorHandler", e)
        }

        EthereumKit.init()

        instance = this
        preferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)

        val appConfig = AppConfigProvider()
        appConfigProvider = appConfig

        marketKit = MarketKit.getInstance(
            this,
            appConfig.marketApiBaseUrl,
            appConfig.marketApiKey,
            cryptoCompareApiKey = appConfig.cryptoCompareApiKey,
            defiYieldApiKey = appConfig.defiyieldProviderApiKey
        )
        marketKit.sync()

        feeRateProvider = FeeRateProvider(appConfigProvider)
        backgroundManager = BackgroundManager(this)

        appDatabase = AppDatabase.getInstance(this)

        evmNetworkManager = EvmNetworkManager(appConfigProvider)
        accountSettingManager = AccountSettingManager(AccountSettingRecordStorage(appDatabase), evmNetworkManager)

        ethereumKitManager = EvmKitManager(appConfig.etherscanApiKey, backgroundManager, EvmNetworkProviderEth(accountSettingManager))
        binanceSmartChainKitManager = EvmKitManager(appConfig.bscscanApiKey, backgroundManager, EvmNetworkProviderBsc(accountSettingManager))
        binanceKitManager = BinanceKitManager(testMode)

        accountsStorage = AccountsStorage(appDatabase)
        restoreSettingsStorage = RestoreSettingsStorage(appDatabase)

        AppLog.logsDao = appDatabase.logsDao()

        coinManager = CoinManager(marketKit, CustomTokenStorage(appDatabase))

        enabledWalletsStorage = EnabledWalletsStorage(appDatabase)
        blockchainSettingsStorage = BlockchainSettingsStorage(appDatabase)
        walletStorage = WalletStorage(coinManager, enabledWalletsStorage)

        LocalStorageManager(preferences).apply {
            localStorage = this
            chartTypeStorage = this
            pinStorage = this
            thirdKeyboardStorage = this
            marketStorage = this
        }

        torKitManager = TorManager(instance, localStorage)

        wordsManager = WordsManager()
        networkManager = NetworkManager()
        accountCleaner = AccountCleaner(testMode)
        accountManager = AccountManager(accountsStorage, accountCleaner)
        accountFactory = AccountFactory(accountManager)
        backupManager = BackupManager(accountManager)
        walletManager = WalletManager(accountManager, walletStorage)

        KeyStoreManager("MASTER_KEY", KeyStoreCleaner(localStorage, accountManager, walletManager)).apply {
            keyStoreManager = this
            keyProvider = this
        }

        encryptionManager = EncryptionManager(keyProvider)

        systemInfoManager = SystemInfoManager()

        languageManager = LanguageManager()
        currencyManager = CurrencyManager(localStorage, appConfigProvider)
        numberFormatter = NumberFormatter(languageManager)

        connectivityManager = ConnectivityManager(backgroundManager)

        val zcashBirthdayProvider = ZcashBirthdayProvider(this, testMode)
        restoreSettingsManager = RestoreSettingsManager(restoreSettingsStorage, zcashBirthdayProvider)

        val adapterFactory = AdapterFactory(instance, testMode, ethereumKitManager, binanceSmartChainKitManager, binanceKitManager, backgroundManager, restoreSettingsManager, coinManager)
        adapterManager = AdapterManager(walletManager, adapterFactory, ethereumKitManager, binanceSmartChainKitManager, binanceKitManager)
        transactionAdapterManager = TransactionAdapterManager(adapterManager, adapterFactory)

        initialSyncModeSettingsManager = InitialSyncSettingsManager(blockchainSettingsStorage, adapterManager, walletManager)

        adapterFactory.initialSyncModeSettingsManager = initialSyncModeSettingsManager

        feeCoinProvider = FeeCoinProvider(marketKit)

        addressParserFactory = AddressParserFactory()

        pinComponent = PinComponent(
                pinStorage = pinStorage,
                encryptionManager = encryptionManager,
                excludedActivityNames = listOf(
                        KeyStoreActivity::class.java.name,
                        LockScreenActivity::class.java.name,
                        LauncherActivity::class.java.name,
                        TorConnectionActivity::class.java.name
                )
        )

        backgroundStateChangeListener = BackgroundStateChangeListener(systemInfoManager, keyStoreManager, pinComponent).apply {
            backgroundManager.registerListener(this)
        }

        rateAppManager = RateAppManager(walletManager, adapterManager, localStorage)

        wc1SessionStorage = WC1SessionStorage(appDatabase)
        wc1SessionManager = WC1SessionManager(wc1SessionStorage, accountManager, accountSettingManager)
        wc1RequestManager = WC1RequestManager()
        wc1Manager = WC1Manager(accountManager, ethereumKitManager, binanceSmartChainKitManager)
        wc2Manager = WC2Manager(accountManager, ethereumKitManager, binanceSmartChainKitManager)

        termsManager = TermsManager(localStorage)

        marketFavoritesManager = MarketFavoritesManager(appDatabase)

        activateCoinManager = ActivateCoinManager(marketKit, walletManager, accountManager)

        releaseNotesManager = ReleaseNotesManager(systemInfoManager, localStorage, appConfigProvider)

        walletActivator = WalletActivator(walletManager, marketKit, walletStorage)

        val enableCoinsErc20Provider = EnableCoinsEip20Provider(
            networkManager,
            EnableCoinsEip20Provider.EnableCoinMode.Erc20,
            appConfig.etherscanApiKey
        )

        val enableCoinsBep20Provider = EnableCoinsEip20Provider(
            networkManager,
            EnableCoinsEip20Provider.EnableCoinMode.Bep20,
            appConfig.bscscanApiKey
        )

        val evmAccountStateDao = appDatabase.evmAccountStateDao()
        AutoEnableTokensService(ethereumKitManager, walletActivator, enableCoinsErc20Provider, evmAccountStateDao).start()
        AutoEnableTokensService(binanceSmartChainKitManager, walletActivator, enableCoinsBep20Provider, evmAccountStateDao).start()

        setAppTheme()

        registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks(torKitManager))

        startTasks()

        nftManager = NftManager(appDatabase.nftCollectionDao(), HsNftApiProvider(), coinManager)

        initializeWalletConnectV2(appConfig)

        wc2Service = WC2Service()
        wc2SessionManager = WC2SessionManager(accountManager, WC2SessionStorage(appDatabase), wc2Service, wc2Manager)
    }

    private fun initializeWalletConnectV2(appConfig: AppConfigProvider) {
        val initWallet = WalletConnect.Params.Init(
            application = this,
            relayServerUrl = "wss://${appConfig.walletConnectUrl}?projectId=${appConfig.walletConnectProjectId}",
            isController = true,
            metadata = WalletConnect.Model.AppMetaData(
                name = "Unstoppable Wallet",
                description = "Wallet description",
                url = "example.wallet",
                icons = listOf("https://gblobscdn.gitbook.com/spaces%2F-LJJeCjcLrr53DcT1Ml7%2Favatar.png?alt=media")
            )
        )

        WalletConnectClient.initialize(initWallet) {
            AppLog.warning("WalletConnect V2 initialization","error", it)
        }
    }

    private fun setAppTheme() {
        val nightMode = when (localStorage.currentTheme) {
            ThemeType.Light -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeType.Dark -> AppCompatDelegate.MODE_NIGHT_YES
            ThemeType.System -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }

        if (AppCompatDelegate.getDefaultNightMode() != nightMode) {
            AppCompatDelegate.setDefaultNightMode(nightMode)
        }
    }

    override fun getWorkManagerConfiguration() =
        WorkConfiguration.Builder()
            .setMinimumLoggingLevel(Log.VERBOSE)
            .build()

    override fun onTrimMemory(level: Int) {
        when (level) {
            TRIM_MEMORY_BACKGROUND,
            TRIM_MEMORY_MODERATE,
            TRIM_MEMORY_COMPLETE -> {
                /*
                   Release as much memory as the process can.

                   The app is on the LRU list and the system is running low on memory.
                   The event raised indicates where the app sits within the LRU list.
                   If the event is TRIM_MEMORY_COMPLETE, the process will be one of
                   the first to be terminated.
                */
                if (backgroundManager.inBackground) {
                    val logger = AppLogger("low memory")
                    logger.info("Kill app due to low memory, level: $level")
                    exitProcess(0)
                }
            }
            else -> {  /*do nothing*/
            }
        }
        super.onTrimMemory(level)
    }

    override fun localizedContext(): Context {
        return localeAwareContext(this)
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(localeAwareContext(base))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        localeAwareContext(this)
    }

    private fun startTasks() {
        Thread {
            rateAppManager.onAppLaunch()
            accountManager.loadAccounts()
            walletManager.loadWallets()
            adapterManager.preloadAdapters()
            accountManager.clearAccounts()

            AppVersionManager(systemInfoManager, localStorage).apply { storeAppVersion() }

            if (!localStorage.customTokensRestoreCompleted) {
                val request = OneTimeWorkRequestBuilder<RestoreCustomTokenWorker>().build()
                WorkManager.getInstance(instance).enqueue(request)
            }

            if (!localStorage.favoriteCoinIdsMigrated){
                val request = OneTimeWorkRequestBuilder<MigrateFavoriteCoinIdsWorker>().build()
                WorkManager.getInstance(instance).enqueue(request)
            }

        }.start()
    }
}
