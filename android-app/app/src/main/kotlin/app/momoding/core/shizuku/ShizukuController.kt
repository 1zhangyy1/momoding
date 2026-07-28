package app.momoding.core.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.net.toUri
import app.momoding.BuildConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class ShizukuController(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(initialSnapshot())
    val state: StateFlow<ShizukuSnapshot> = _state.asStateFlow()

    @Volatile
    private var service: IMomodingShizukuService? = null
    @Volatile
    private var binding = false
    @Volatile
    private var activeConnection: ServiceConnection? = null
    @Volatile
    private var bindScheduled = false
    @Volatile
    private var capabilityChangedListener: (() -> Unit)? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refresh(bindIfEligible = true)
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        service = null
        binding = false
        activeConnection = null
        refresh(bindIfEligible = false)
    }
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, _ ->
            refresh(bindIfEligible = true)
        }
    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(appContext.packageName, MomodingShizukuUserService::class.java.name),
    )
        .daemon(false)
        .tag(USER_SERVICE_TAG)
        .processNameSuffix(USER_SERVICE_PROCESS)
        .debuggable(BuildConfig.DEBUG)
        .version(USER_SERVICE_VERSION)

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        refresh(bindIfEligible = true)
    }

    fun setCapabilityChangedListener(listener: () -> Unit) {
        capabilityChangedListener = listener
    }

    fun refresh() {
        refresh(bindIfEligible = true)
    }

    fun performPrimaryAction(context: Context) {
        when (state.value.stage) {
            ShizukuLifecycleStage.NOT_INSTALLED -> openDownloadPage(context)
            ShizukuLifecycleStage.NOT_RUNNING,
            ShizukuLifecycleStage.ROOT_REJECTED,
            ShizukuLifecycleStage.UNSUPPORTED_VERSION -> openManager(context)
            ShizukuLifecycleStage.PERMISSION_REQUIRED -> requestPermission()
            ShizukuLifecycleStage.CONNECTING,
            ShizukuLifecycleStage.READY,
            ShizukuLifecycleStage.ERROR -> refresh()
        }
    }

    fun requestPermission(): Boolean {
        val facts = readPrerequisites()
        if (
            !facts.managerInstalled ||
            !facts.binderAlive ||
            facts.preV11 ||
            facts.serverApiVersion == null ||
            facts.serverApiVersion < MIN_SHIZUKU_SERVER_API ||
            facts.serverUid != ANDROID_SHELL_UID ||
            facts.permissionGranted
        ) {
            refresh(bindIfEligible = true)
            return false
        }
        return runCatching {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            true
        }.getOrDefault(false)
    }

    suspend fun listPackages(
        includeSystem: Boolean,
        offset: Int,
        limit: Int,
    ): ShizukuQueryResult<List<ShizukuPackageSummary>> = withContext(ioDispatcher) {
        if (!validShizukuPackagePage(offset, limit)) {
            return@withContext ShizukuQueryResult.Failed(
                code = "INVALID_ARGUMENT",
                safeMessage = "Package query bounds are invalid.",
            )
        }
        val remote = readyService()
            ?: return@withContext notReadyResult()
        runCatching {
            remote.listPackages(includeSystem, offset, limit)
                .also { require(it.size <= limit) { "service returned too many packages" } }
                .also { packages ->
                    require(packages.zipWithNext().all { (left, right) ->
                        left.packageName <= right.packageName
                    }) { "service returned unsorted packages" }
                }
        }.fold(
            onSuccess = { ShizukuQueryResult.Ready(it) },
            onFailure = {
                handleRemoteFailure()
                ShizukuQueryResult.Failed(
                    code = "QUERY_FAILED",
                    safeMessage = "Android package list could not be read.",
                )
            },
        )
    }

    suspend fun inspectPackage(
        packageName: String,
    ): ShizukuQueryResult<ShizukuPackageDetails> = withContext(ioDispatcher) {
        if (!validShizukuPackageName(packageName)) {
            return@withContext ShizukuQueryResult.Failed(
                code = "INVALID_ARGUMENT",
                safeMessage = "Package name is invalid.",
            )
        }
        val remote = readyService()
            ?: return@withContext notReadyResult()
        runCatching<ShizukuQueryResult<ShizukuPackageDetails>> {
            val details = remote.inspectPackage(packageName)
                ?: return@runCatching ShizukuQueryResult.Failed(
                    code = "PACKAGE_NOT_FOUND",
                    safeMessage = "The requested Android package is not installed.",
                )
            require(details.packageName == packageName) { "service returned wrong package" }
            ShizukuQueryResult.Ready(details)
        }.getOrElse {
            handleRemoteFailure()
            ShizukuQueryResult.Failed(
                code = "QUERY_FAILED",
                safeMessage = "Android package details could not be read.",
            )
        }
    }

    fun stopUserService() {
        val connection = activeConnection
        service = null
        binding = false
        activeConnection = null
        runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
        refresh(bindIfEligible = false)
    }

    private fun readyService(): IMomodingShizukuService? {
        refresh(bindIfEligible = false)
        return service.takeIf { state.value.stage == ShizukuLifecycleStage.READY }
    }

    private fun notReadyResult(): ShizukuQueryResult.Failed =
        ShizukuQueryResult.Failed(
            code = "SHIZUKU_NOT_READY",
            safeMessage = state.value.safeMessage,
        )

    private fun handleRemoteFailure() {
        val connection = activeConnection
        service = null
        binding = false
        activeConnection = null
        if (connection != null) {
            runCatching { Shizuku.unbindUserService(userServiceArgs, connection, false) }
        }
        refresh(bindIfEligible = true)
    }

    private fun refresh(bindIfEligible: Boolean) {
        val facts = readPrerequisites()
        val next = shizukuSnapshot(facts)
        val changed = _state.value != next
        _state.value = next
        if (changed) capabilityChangedListener?.invoke()
        if (bindIfEligible && next.stage == ShizukuLifecycleStage.CONNECTING) {
            scheduleBindUserService()
        }
    }

    private fun scheduleBindUserService() {
        if (bindScheduled || binding || service != null) return
        bindScheduled = true
        mainHandler.post {
            bindScheduled = false
            if (state.value.stage == ShizukuLifecycleStage.CONNECTING) {
                bindUserServiceNow()
            }
        }
    }

    private fun bindUserServiceNow() {
        if (binding || service != null) return
        binding = true
        val connection = newServiceConnection()
        activeConnection = connection
        runCatching {
            Shizuku.bindUserService(userServiceArgs, connection)
        }.onFailure {
            binding = false
            if (activeConnection === connection) activeConnection = null
            refresh(bindIfEligible = false)
        }
    }

    private fun newServiceConnection() = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (activeConnection !== this) return
            binding = false
            val candidate = IMomodingShizukuService.Stub.asInterface(binder)
            val binderAlive = binder.pingBinder()
            val reportedUid = runCatching {
                candidate.processUid
            }.onFailure {
                Log.w(TAG, "UserService identity check failed", it)
            }.getOrNull()
            val accepted = binderAlive && reportedUid == ANDROID_SHELL_UID
            Log.i(TAG, "UserService connected alive=$binderAlive uid=$reportedUid accepted=$accepted")
            service = candidate.takeIf { accepted }
            if (!accepted) {
                activeConnection = null
                runCatching { Shizuku.unbindUserService(userServiceArgs, this, true) }
            }
            refresh(bindIfEligible = false)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            if (activeConnection !== this) return
            service = null
            binding = false
            activeConnection = null
            refresh(bindIfEligible = true)
        }
    }

    private fun readPrerequisites(): ShizukuPrerequisites {
        val installed = isManagerInstalled()
        if (!installed) return basePrerequisites(managerInstalled = false)
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!binderAlive) {
            service = null
            binding = false
            return basePrerequisites(managerInstalled = true)
        }
        return runCatching {
            val preV11 = Shizuku.isPreV11()
            val serverUid = Shizuku.getUid()
            val permissionGranted =
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            val currentService = service
            val serviceUid = currentService?.let { runCatching { it.processUid }.getOrNull() }
            val servicePid = currentService?.let { runCatching { it.processPid }.getOrNull() }
            ShizukuPrerequisites(
                managerInstalled = true,
                binderAlive = true,
                preV11 = preV11,
                permissionGranted = permissionGranted,
                serverUid = serverUid,
                serverApiVersion = Shizuku.getVersion(),
                clientApiVersion = Shizuku.getLatestServiceVersion(),
                serviceUid = serviceUid,
                servicePid = servicePid,
                serviceConnected = currentService != null,
            )
        }.getOrElse {
            service = null
            binding = false
            basePrerequisites(managerInstalled = true, binderAlive = true, failed = true)
        }
    }

    private fun basePrerequisites(
        managerInstalled: Boolean,
        binderAlive: Boolean = false,
        failed: Boolean = false,
    ) = ShizukuPrerequisites(
        managerInstalled = managerInstalled,
        binderAlive = binderAlive,
        preV11 = false,
        permissionGranted = false,
        serverUid = null,
        serverApiVersion = null,
        clientApiVersion = Shizuku.getLatestServiceVersion(),
        serviceUid = null,
        servicePid = null,
        serviceConnected = false,
        failed = failed,
    )

    private fun isManagerInstalled(): Boolean = runCatching {
        @Suppress("DEPRECATION")
        appContext.packageManager.getPackageInfo(SHIZUKU_MANAGER_PACKAGE, 0)
        true
    }.getOrDefault(false)

    private fun openManager(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_MANAGER_PACKAGE)
        if (launch != null) {
            runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        } else {
            openDownloadPage(context)
        }
    }

    private fun openDownloadPage(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, SHIZUKU_DOWNLOAD_URL.toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private fun initialSnapshot() = ShizukuSnapshot(
        stage = ShizukuLifecycleStage.NOT_INSTALLED,
        safeMessage = "Checking Shizuku access.",
    )

    companion object {
        private const val PERMISSION_REQUEST_CODE = 0x4D4F
        private const val TAG = "ShizukuController"
        private const val USER_SERVICE_TAG = "momoding.package-query.v1"
        private const val USER_SERVICE_PROCESS = "momoding_shizuku"
        private const val USER_SERVICE_VERSION = 1
        private const val SHIZUKU_DOWNLOAD_URL = "https://shizuku.rikka.app/download/"
    }
}
