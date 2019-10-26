package github.tornaco.android.thanos.services.secure

import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.location.Location
import android.os.IBinder
import android.telephony.TelephonyManager
import android.text.TextUtils
import github.tornaco.android.thanos.BuildProp
import github.tornaco.android.thanos.core.Res
import github.tornaco.android.thanos.core.T
import github.tornaco.android.thanos.core.T.Tags.N_TAG_PKG_PRIVACY_DATA_CHEATING
import github.tornaco.android.thanos.core.app.AppResources
import github.tornaco.android.thanos.core.app.event.IEventSubscriber
import github.tornaco.android.thanos.core.app.event.ThanosEvent
import github.tornaco.android.thanos.core.compat.NotificationCompat
import github.tornaco.android.thanos.core.compat.NotificationManagerCompat
import github.tornaco.android.thanos.core.persist.RepoFactory
import github.tornaco.android.thanos.core.persist.StringMapRepo
import github.tornaco.android.thanos.core.persist.StringSetRepo
import github.tornaco.android.thanos.core.pm.PackageManager
import github.tornaco.android.thanos.core.pref.IPrefChangeListener
import github.tornaco.android.thanos.core.secure.IPrivacyManager
import github.tornaco.android.thanos.core.util.*
import github.tornaco.android.thanos.services.S
import github.tornaco.android.thanos.services.SystemService
import github.tornaco.android.thanos.services.ThanosSchedulers
import github.tornaco.android.thanos.services.app.EventBus
import github.tornaco.android.thanos.services.n.NotificationHelper
import github.tornaco.android.thanos.services.n.NotificationIdFactory
import github.tornaco.android.thanos.services.n.SystemUI
import github.tornaco.java.common.util.ObjectsUtils
import io.reactivex.Completable
import io.reactivex.Single
import io.reactivex.SingleOnSubscribe
import java.util.concurrent.TimeUnit

class PrivacyService(private val s: S) : SystemService(), IPrivacyManager {

    private val notificationHelper: NotificationHelper = NotificationHelper()

    private lateinit var pkgDeviceIdRepo: StringMapRepo
    private lateinit var pkgSimNumRepo: StringMapRepo
    private lateinit var pkgLine1NumRepo: StringMapRepo
    private lateinit var installedPkgsReturnEmptyRepo: StringMapRepo

    private lateinit var privacyDataCheatPkgRepo: StringSetRepo

    private var privacyEnabled = false

    private var privacyRequestHandleTimes = 0L

    private val frontEventSubscriber = object : IEventSubscriber.Stub() {
        override fun onEvent(e: ThanosEvent) {
            val intent = e.intent
            val to = intent.getStringExtra(T.Actions.ACTION_FRONT_PKG_CHANGED_EXTRA_PACKAGE_TO)
            onFrontPkgChanged(to)
        }
    }

    override fun onStart(context: Context) {
        super.onStart(context)

        pkgDeviceIdRepo = RepoFactory.get().getOrCreateStringMapRepo(T.privacyDeviceIdFile().path)
        pkgLine1NumRepo = RepoFactory.get().getOrCreateStringMapRepo(T.privacyLine1NumFile().path)
        pkgSimNumRepo = RepoFactory.get().getOrCreateStringMapRepo(T.privacySimNumFile().path)
        installedPkgsReturnEmptyRepo =
            RepoFactory.get().getOrCreateStringMapRepo(T.privacyInstalledPkgsReturnEmptyFile().path)

        privacyDataCheatPkgRepo = RepoFactory.get().getOrCreateStringSetRepo(T.privacyPkgSettingsFile().path)
    }

    override fun systemReady() {
        super.systemReady()
        registerReceivers()
        initPrefs()
    }

    private fun initPrefs() {
        readPrefs()
        listenToPrefs()
    }

    private fun readPrefs() {
        val preferenceManagerService = s.preferenceManagerService
        this.privacyEnabled = preferenceManagerService.getBoolean(
            T.Settings.PREF_PRIVACY_ENABLED.key,
            T.Settings.PREF_PRIVACY_ENABLED.defaultValue
        )
    }

    private fun listenToPrefs() {
        val listener = object : IPrefChangeListener.Stub() {
            override fun onPrefChanged(key: String) {
                if (ObjectsUtils.equals(T.Settings.PREF_PRIVACY_ENABLED.key, key)) {
                    Timber.i("Pref changed, reload.")
                    readPrefs()
                }
            }
        }
        s.preferenceManagerService.registerSettingsChangeListener(listener)
    }

    override fun isPrivacyEnabled(): Boolean {
        return privacyEnabled
    }

    override fun setPrivacyEnabled(enabled: Boolean) {
        privacyEnabled = enabled

        val preferenceManagerService = s.preferenceManagerService
        preferenceManagerService.putBoolean(
            T.Settings.PREF_PRIVACY_ENABLED.key,
            enabled
        )
    }

    override fun isUidPrivacyDataCheat(uid: Int): Boolean {
        val pkgArr = s.pkgManagerService.getPkgNameForUid(uid)
        for (pkg in pkgArr!!) {
            if (isPkgPrivacyDataCheat(pkg)) return true
        }
        return false
    }

    override fun isPkgPrivacyDataCheat(pkg: String): Boolean {
        return if (s.pkgManagerService.isPkgInWhiteList(pkg)
            || PackageManager.packageNameOfAndroid() == pkg
            || PackageManager.packageNameOfPhone() == pkg
            || PackageManager.packageNameOfTelecom() == pkg
        ) {
            false
        } else {
            privacyDataCheatPkgRepo.has(pkg)
        }
    }

    override fun setPkgPrivacyDataCheat(pkg: String, enable: Boolean) {
        if (enable) privacyDataCheatPkgRepo.add(pkg) else privacyDataCheatPkgRepo.remove(pkg)
    }

    override fun getCheatedInstalledPackagesForUid(uid: Int): Array<PackageInfo?>? {
        val pkgNames: Array<String> = s.pkgManagerService.getPkgNameForUid(uid)
        if (ArrayUtils.isEmpty(pkgNames)) {
            Timber.e("Pkg name not found for uid: $uid")
            return null
        }

        val pkgName = pkgNames[0]
        Timber.v("getInstalledPackages: $pkgName")

        val useSet = installedPkgsReturnEmptyRepo[pkgName]
        if (!TextUtils.isEmpty(useSet) && true.toString() == useSet) {
            return arrayOfNulls(0)
        }
        val allSet = installedPkgsReturnEmptyRepo["*"]
        Timber.v("getCheatedInstalledApplicationsUid, allSet: $allSet")
        if (!TextUtils.isEmpty(allSet) && true.toString() == allSet) {
            return arrayOfNulls(0)
        }
        // Do not cheat.
        return null
    }

    override fun getCheatedInstalledApplicationsUid(uid: Int): Array<ApplicationInfo?>? {
        val pkgNames: Array<String> = s.pkgManagerService.getPkgNameForUid(uid)
        if (ArrayUtils.isEmpty(pkgNames)) {
            Timber.e("Pkg name not found for uid: $uid")
            return null
        }

        val pkgName = pkgNames[0]
        Timber.v("getInstalledApplications: $pkgName")

        val useSet = installedPkgsReturnEmptyRepo[pkgName]
        if (!TextUtils.isEmpty(useSet) && true.toString() == useSet) {
            return arrayOfNulls(0)
        }
        val allSet = installedPkgsReturnEmptyRepo["*"]
        Timber.v("getCheatedInstalledApplicationsUid, allSet: $allSet")
        if (!TextUtils.isEmpty(allSet) && true.toString() == allSet) {
            return arrayOfNulls(0)
        }
        // Do not cheat.
        return null
    }

    override fun isInstalledPackagesReturnEmptyEnableForPkg(pkg: String?): Boolean {
        val set = installedPkgsReturnEmptyRepo[pkg]
        return !TextUtils.isEmpty(set) && true.toString() == set
    }

    override fun setInstalledPackagesReturnEmptyEnableForPkg(pkg: String?, enable: Boolean) {
        installedPkgsReturnEmptyRepo[pkg] = enable.toString()
    }

    override fun getCheatedDeviceIdForPkg(pkg: String): String? {
        privacyRequestHandleTimes++
        val useSet = pkgDeviceIdRepo[pkg]
        if (!TextUtils.isEmpty(useSet)) {
            return useSet
        }
        val allSet = pkgDeviceIdRepo["*"]
        if (!TextUtils.isEmpty(allSet)) {
            return allSet
        }
        return null
    }

    override fun getCheatedLine1NumberForPkg(pkg: String): String? {
        privacyRequestHandleTimes++
        val useSet = pkgLine1NumRepo[pkg]
        if (!TextUtils.isEmpty(useSet)) {
            return useSet
        }
        val allSet = pkgLine1NumRepo["*"]
        if (!TextUtils.isEmpty(allSet)) {
            return allSet
        }
        return null
    }

    override fun getCheatedSimSerialNumberForPkg(pkg: String): String? {
        privacyRequestHandleTimes++
        val useSet = pkgSimNumRepo[pkg]
        if (!TextUtils.isEmpty(useSet)) {
            return useSet
        }
        val allSet = pkgSimNumRepo["*"]
        if (!TextUtils.isEmpty(allSet)) {
            return allSet
        }
        return null
    }

    override fun getCheatedLocationForPkg(pkg: String?, actual: Location?): Location {
        privacyRequestHandleTimes++
        val res = Location(actual)
        res.latitude = 99.0
        res.longitude = 99.0
        res.accuracy = 1f
        return res
    }

    override fun setCheatedDeviceIdForPkg(pkg: String, deviceId: String) {
        pkgDeviceIdRepo[pkg] = deviceId
    }

    override fun setCheatedLine1NumberForPkg(pkg: String, num: String) {
        pkgLine1NumRepo[pkg] = num
    }

    override fun setCheatedSimSerialNumberForPkg(pkg: String, num: String) {
        pkgSimNumRepo[pkg] = num
    }

    @SuppressLint("HardwareIds")
    override fun getOriginalDeviceId(): String {
        return Single
            .create(SingleOnSubscribe<String> { emitter ->
                emitter.onSuccess(Optional.ofNullable(TelephonyManager.from(context).deviceId).orElse(NPEFixing.emptyString()))
            })
            .subscribeOn(ThanosSchedulers.serverThread())
            .timeout(300, TimeUnit.MILLISECONDS)
            .onErrorReturnItem(NPEFixing.emptyString())
            .blockingGet()
    }

    @SuppressLint("HardwareIds")
    override fun getOriginalLine1Number(): String {
        return Single
            .create(SingleOnSubscribe<String> { emitter ->
                emitter.onSuccess(Optional.ofNullable(TelephonyManager.from(context).line1Number).orElse(NPEFixing.emptyString()))
            })
            .subscribeOn(ThanosSchedulers.serverThread())
            .timeout(300, TimeUnit.MILLISECONDS)
            .onErrorReturnItem(NPEFixing.emptyString())
            .blockingGet()
    }

    @SuppressLint("HardwareIds")
    override fun getOriginalSimSerialNumber(): String {
        return Single
            .create(SingleOnSubscribe<String> { emitter ->
                emitter.onSuccess(Optional.ofNullable(TelephonyManager.from(context).simSerialNumber).orElse(NPEFixing.emptyString()))
            })
            .subscribeOn(ThanosSchedulers.serverThread())
            .timeout(300, TimeUnit.MILLISECONDS)
            .onErrorReturnItem(NPEFixing.emptyString())
            .blockingGet()
    }

    override fun getPrivacyDataCheatRequestCount(): Long {
        return privacyRequestHandleTimes
    }

    override fun getPrivacyDataCheatPkgCount(): Int {
        return privacyDataCheatPkgRepo.size()
    }

    override fun asBinder(): IBinder {
        return Noop.notSupported()
    }

    private fun registerReceivers() {
        EventBus.getInstance().registerEventSubscriber(
            IntentFilter(T.Actions.ACTION_FRONT_PKG_CHANGED), frontEventSubscriber
        )
    }

    private fun onFrontPkgChanged(pkg: String) {
        Timber.v("onFrontPkgChanged: %s", pkg)
        Completable
            .fromRunnable { showPackagePrivacyDataCheatingNotification(pkg) }
            .subscribeOn(ThanosSchedulers.serverThread())
            .subscribe()
    }

    private fun showPackagePrivacyDataCheatingNotification(pkg: String) {
        if (!isNotificationPostReady) return

        // Cancel first.
        NotificationManagerCompat.from(context)
            .cancel(NotificationIdFactory.getIdByTag(N_TAG_PKG_PRIVACY_DATA_CHEATING))

        if (!isPkgPrivacyDataCheat(pkg)) {
            return
        }

        Timber.v("Will show showPackagePrivacyDataCheatingNotification for pkg: %s", pkg)

        // For oreo.
        notificationHelper.createSilenceNotificationChannel(context!!)

        val builder = NotificationCompat.Builder(
            context,
            T.serviceSilenceNotificationChannel()
        )

        val appResource = AppResources(context, BuildProp.THANOS_APP_PKG_NAME)

        SystemUI.overrideNotificationAppName(
            context!!, builder,
            appResource.getString(Res.Strings.STRING_SERVICE_NOTIFICATION_OVERRIDE_THANOS)
        )

        val appLabel = s.pkgManagerService.getAppInfo(pkg).appLabel

        // TODO Extract string res.
        val n = builder
            .setContentTitle("隐私防护")
            .setContentText(appLabel + "已被限制访问真实隐私数据")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        if (OsUtils.isMOrAbove()) {
            n.smallIcon = appResource.getIcon(Res.Drawables.DRAWABLE_EYE_CLOSE_FILL)
        }

        NotificationManagerCompat.from(context)
            .notify(NotificationIdFactory.getIdByTag(N_TAG_PKG_PRIVACY_DATA_CHEATING), n)
    }
}
