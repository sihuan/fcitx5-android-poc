package me.rocka.fcitx5test.native

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import me.rocka.fcitx5test.copyFileOrDir
import me.rocka.fcitx5test.native.FcitxState.*

class Fcitx(private val context: Context) : FcitxLifecycleOwner {

    interface RawConfigMap {
        operator fun get(key: String): RawConfig?
        operator fun set(key: String, value: RawConfig)
    }

    override val currentState: FcitxState
        get() = fcitxState_

    @Volatile
    override var observer: FcitxLifecycleObserver? = null
        set(value) {
            onStateChanged = {
                when (it) {
                    Starting to Ready -> value?.onReady()
                    Stopping to Stopped -> value?.onStopped()
                }
            }
            field = value
        }

    private var fcitxJob: Job? = null

    /**
     * Subscribe this flow to receive event sent from fcitx
     */
    val eventFlow = eventFlow_.asSharedFlow()

    fun saveConfig() = saveFcitxConfig()
    fun sendKey(key: String) = sendKeyToFcitxString(key)
    fun sendKey(c: Char) = sendKeyToFcitxChar(c)
    fun select(idx: Int) = selectCandidate(idx)
    fun isEmpty() = isInputPanelEmpty()
    fun reset() = resetInputPanel()
    fun listIme() = listInputMethods()
    fun imeStatus() = inputMethodStatus()
    fun setIme(ime: String) = setInputMethod(ime)
    fun availableIme() = availableInputMethods()
    fun setEnabledIme(array: Array<String>) = setEnabledInputMethods(array)
    var globalConfig: RawConfig
        get() = getFcitxGlobalConfig()
        set(value) = setFcitxGlobalConfig(value)
    var addonConfig = object : RawConfigMap {
        override operator fun get(key: String) = getFcitxAddonConfig(key)
        override operator fun set(key: String, value: RawConfig) = setFcitxAddonConfig(key, value)
    }
    var imConfig = object : RawConfigMap {
        override operator fun get(key: String) = getFcitxInputMethodConfig(key)
        override operator fun set(key: String, value: RawConfig) =
            setFcitxInputMethodConfig(key, value)
    }

    fun addons() = getFcitxAddons()
    fun setAddonState(name: Array<String>, state: BooleanArray) = setFcitxAddonState(name, state)
    fun triggerQuickPhrase() = triggerQuickPhraseInput()

    init {
        if (fcitxState_ != Stopped)
            throw IllegalAccessException("Fcitx5 is already running!")
    }

    private companion object JNI :
        CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.IO) {

        @Volatile
        private var fcitxState_ = Stopped
            set(value) {
                onStateChanged(field to value)
                field = value
            }

        private var onStateChanged: (Pair<FcitxState, FcitxState>) -> Unit = {}

        private val eventFlow_ =
            MutableSharedFlow<FcitxEvent<*>>(
                extraBufferCapacity = 15,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )


        init {
            System.loadLibrary("native-lib")
        }

        @JvmStatic
        external fun startupFcitx(appData: String, appLib: String, extData: String): Int

        @JvmStatic
        external fun exitFcitx()

        @JvmStatic
        external fun saveFcitxConfig()

        @JvmStatic
        external fun sendKeyToFcitxString(key: String)

        @JvmStatic
        external fun sendKeyToFcitxChar(c: Char)

        @JvmStatic
        external fun selectCandidate(idx: Int)

        @JvmStatic
        external fun isInputPanelEmpty(): Boolean

        @JvmStatic
        external fun resetInputPanel()

        @JvmStatic
        external fun listInputMethods(): Array<InputMethodEntry>

        @JvmStatic
        external fun inputMethodStatus(): InputMethodEntry

        @JvmStatic
        external fun setInputMethod(ime: String)

        @JvmStatic
        external fun availableInputMethods(): Array<InputMethodEntry>

        @JvmStatic
        external fun setEnabledInputMethods(array: Array<String>)

        @JvmStatic
        external fun getFcitxGlobalConfig(): RawConfig

        @JvmStatic
        external fun getFcitxAddonConfig(addon: String): RawConfig?

        @JvmStatic
        external fun getFcitxInputMethodConfig(im: String): RawConfig?

        @JvmStatic
        external fun setFcitxGlobalConfig(config: RawConfig)

        @JvmStatic
        external fun setFcitxAddonConfig(addon: String, config: RawConfig)

        @JvmStatic
        external fun setFcitxInputMethodConfig(im: String, config: RawConfig)

        @JvmStatic
        external fun getFcitxAddons(): Array<AddonInfo>

        @JvmStatic
        external fun setFcitxAddonState(name: Array<String>, state: BooleanArray)

        @JvmStatic
        external fun triggerQuickPhraseInput()

        /**
         * Called from native-lib
         */
        @Suppress("unused")
        @JvmStatic
        fun handleFcitxEvent(type: Int, vararg params: Any) {
            Log.d(
                "FcitxEvent",
                "type=${type}, params=[${params.size}]${params.take(10).joinToString()}"
            )
            val event = FcitxEvent.create(type, params.asList())
            if (event is FcitxEvent.ReadyEvent)
                fcitxState_ = Ready
            eventFlow_.tryEmit(event)
        }
    }

    override fun start() {
        if (fcitxState_ != Stopped)
            return
        fcitxState_ = Starting
        with(context) {
            fcitxJob = launch {
                copyFileOrDir("fcitx5")
                val externalFilesDir = getExternalFilesDir(null)!!
                startupFcitx(
                    applicationInfo.dataDir,
                    applicationInfo.nativeLibraryDir,
                    externalFilesDir.absolutePath
                )
            }
        }
    }

    override fun stop() {
        if (fcitxState_ != Ready)
            return
        fcitxState_ = Stopping
        exitFcitx()
        runBlocking {
            fcitxJob?.cancelAndJoin()
        }
        fcitxJob = null
        fcitxState_ = Stopped
    }

}