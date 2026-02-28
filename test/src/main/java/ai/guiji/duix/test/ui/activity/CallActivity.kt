package ai.guiji.duix.test.ui.activity

import ai.guiji.duix.sdk.client.Constant
import ai.guiji.duix.sdk.client.DUIX
import ai.guiji.duix.sdk.client.loader.ModelInfo
import ai.guiji.duix.sdk.client.render.DUIXRenderer
import ai.guiji.duix.test.R
import ai.guiji.duix.test.databinding.ActivityCallBinding
import ai.guiji.duix.test.net.DashScopeService
import ai.guiji.duix.test.ui.adapter.MotionAdapter
import ai.guiji.duix.test.ui.dialog.AudioRecordDialog
import ai.guiji.duix.test.util.StringUtils
import android.Manifest
import android.annotation.SuppressLint
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.widget.CompoundButton
import android.widget.Toast
import com.bumptech.glide.Glide
import java.io.File
import java.io.FileOutputStream


class CallActivity : BaseActivity() {

    companion object {
        const val GL_CONTEXT_VERSION = 2
    }

    private var modelUrl = ""
    private var debug = false
    private var mMessage = ""

    @SuppressLint("SetTextI18n")
    private fun applyMessage(msg: String) {
        if (debug) {
            runOnUiThread {
                binding.tvDebug.visibility = View.VISIBLE
                if (mMessage.length > 10000) mMessage = ""
                mMessage = "${StringUtils.dateToStringMS4()} $msg\n$mMessage"
                binding.tvDebug.text = mMessage
            }
        }
    }

    private lateinit var binding: ActivityCallBinding
    private var duix: DUIX? = null
    private var mDUIXRender: DUIXRenderer? = null
    private var mModelInfo: ModelInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepScreenOn()
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        modelUrl = intent.getStringExtra("modelUrl") ?: ""
        debug = intent.getBooleanExtra("debug", false)

        Glide.with(mContext).load("file:///android_asset/bg/bg1.png").into(binding.ivBg)

        binding.glTextureView.setEGLContextClientVersion(GL_CONTEXT_VERSION)
        binding.glTextureView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        binding.glTextureView.isOpaque = false

        binding.switchMute.setOnCheckedChangeListener(object : CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(buttonView: CompoundButton?, isChecked: Boolean) {
                duix?.setVolume(if (isChecked) 0.0F else 1.0F)
            }
        })

        // 录音按钮：点击申请权限，权限通过后弹录音对话框
        binding.btnRecord.setOnClickListener {
            requestPermission(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        binding.btnPlayPCM.setOnClickListener {
            applyMessage("start play pcm")
            playPCMStream()
        }

        binding.btnPlayWAV.setOnClickListener {
            applyMessage("start play wav")
            playWAVFile()
        }

        binding.btnRandomMotion.setOnClickListener {
            applyMessage("start random motion")
            duix?.startRandomMotion(true)
        }

        binding.btnStopPlay.setOnClickListener {
            duix?.stopAudio()
        }

        mDUIXRender = DUIXRenderer(mContext, binding.glTextureView)
        binding.glTextureView.setRenderer(mDUIXRender)
        binding.glTextureView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        duix = DUIX(mContext, modelUrl, mDUIXRender) { event, msg, info ->
            when (event) {
                Constant.CALLBACK_EVENT_INIT_READY -> {
                    mModelInfo = info as ModelInfo
                    Log.i(TAG, "CALLBACK_EVENT_INIT_READY: $mModelInfo")
                    initOk()
                }
                Constant.CALLBACK_EVENT_INIT_ERROR -> {
                    runOnUiThread {
                        applyMessage("init error: $msg")
                        Log.e(TAG, "CALLBACK_EVENT_INIT_ERROR: $msg")
                        Toast.makeText(mContext, "初始化失败: $msg", Toast.LENGTH_SHORT).show()
                    }
                }
                Constant.CALLBACK_EVENT_AUDIO_PLAY_START -> {
                    applyMessage("callback audio play start")
                }
                Constant.CALLBACK_EVENT_AUDIO_PLAY_END -> {
                    applyMessage("callback audio play end")
                    // 播放完毕，恢复录音按钮
                    runOnUiThread { setRecordEnabled(true) }
                }
                Constant.CALLBACK_EVENT_AUDIO_PLAY_ERROR -> {
                    applyMessage("callback audio play error: $msg")
                    runOnUiThread { setRecordEnabled(true) }
                }
                Constant.CALLBACK_EVENT_MOTION_START -> applyMessage("callback motion play start")
                Constant.CALLBACK_EVENT_MOTION_END -> applyMessage("callback motion play end")
            }
        }

        applyMessage("start init")
        duix?.init()
    }

    private fun initOk() {
        Log.i(TAG, "init ok")
        applyMessage("init ok")
        runOnUiThread {
            // 显示并启用录音按钮
            binding.btnRecord.visibility = View.VISIBLE
            binding.btnRecord.isEnabled = true
            binding.btnPlayPCM.isEnabled = true
            binding.btnPlayWAV.isEnabled = true
            binding.switchMute.isEnabled = true
            binding.btnStopPlay.isEnabled = true

            mModelInfo?.let { modelInfo ->
                if (modelInfo.motionRegions.isNotEmpty()) {
                    val names = ArrayList<String>()
                    for (motion in modelInfo.motionRegions) {
                        if (!TextUtils.isEmpty(motion.name) && "unknown" != motion.name) {
                            names.add(motion.name)
                        }
                    }
                    if (names.isNotEmpty()) {
                        val motionAdapter = MotionAdapter(names, object : MotionAdapter.Callback {
                            override fun onClick(name: String, now: Boolean) {
                                applyMessage("start [$name] motion")
                                duix?.startMotion(name, now)
                            }
                        })
                        binding.rvMotion.adapter = motionAdapter
                    }
                    binding.btnRandomMotion.visibility = View.VISIBLE
                    binding.tvMotionTips.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        duix?.release()
    }

    // ─────────────────────────────────────────────────────────
    // AI 对话流水线：录音 → ASR → LLM → TTS → DUIX
    // ─────────────────────────────────────────────────────────

    override fun permissionsGet(get: Boolean, code: Int) {
        super.permissionsGet(get, code)
        if (get) {
            showRecordDialog()
        } else {
            Toast.makeText(mContext, R.string.need_permission_continue, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRecordDialog() {
        val audioRecordDialog = AudioRecordDialog(mContext, object : AudioRecordDialog.Listener {
            override fun onFinish(path: String) {
                runAIPipeline(path)
            }
        })
        audioRecordDialog.show()
    }

    private fun runAIPipeline(pcmPath: String) {
        setRecordEnabled(false)
        setAIStatus("正在识别语音...")
        applyMessage("ASR start: $pcmPath")

        // Step 1: ASR
        DashScopeService.recognize(pcmPath) { text ->
            if (text.isNullOrBlank()) {
                applyMessage("ASR failed")
                runOnUiThread {
                    setAIStatus("语音识别失败，请重试")
                    setRecordEnabled(true)
                }
                return@recognize
            }
            applyMessage("ASR result: $text")
            runOnUiThread { setAIStatus("已识别: $text\n正在思考...") }

            // Step 2: LLM
            DashScopeService.chat(text) { reply ->
                if (reply.isNullOrBlank()) {
                    applyMessage("LLM failed")
                    runOnUiThread {
                        setAIStatus("大模型回答失败，请重试")
                        setRecordEnabled(true)
                    }
                    return@chat
                }
                applyMessage("LLM reply: $reply")
                runOnUiThread { setAIStatus("正在合成语音...") }

                // Step 3: TTS → 流式推给 DUIX
                DashScopeService.tts(
                    text = reply,
                    onStart = {
                        runOnUiThread { setAIStatus("数字人: $reply") }
                        duix?.startPush()
                    },
                    onPcmChunk = { pcm ->
                        duix?.pushPcm(pcm)
                    },
                    onEnd = {
                        duix?.stopPush()
                        // 播放完毕的状态恢复由 CALLBACK_EVENT_AUDIO_PLAY_END 负责
                    },
                    onError = { err ->
                        applyMessage("TTS error: $err")
                        runOnUiThread {
                            setAIStatus("语音合成失败: $err")
                            setRecordEnabled(true)
                        }
                    }
                )
            }
        }
    }

    private fun setAIStatus(status: String) {
        runOnUiThread {
            if (status.isEmpty()) {
                binding.tvAIStatus.visibility = View.GONE
            } else {
                binding.tvAIStatus.text = status
                binding.tvAIStatus.visibility = View.VISIBLE
            }
        }
    }

    private fun setRecordEnabled(enabled: Boolean) {
        runOnUiThread {
            binding.btnRecord.isEnabled = enabled
            if (enabled) setAIStatus("")
        }
    }

    // ─────────────────────────────────────────────────────────
    // 本地测试播放（原有功能保留）
    // ─────────────────────────────────────────────────────────

    private fun playPCMStream() {
        val thread = Thread {
            duix?.startPush()
            val inputStream = assets.open("pcm/2.pcm")
            val buffer = ByteArray(320)
            var length = 0
            while (inputStream.read(buffer).also { length = it } > 0) {
                duix?.pushPcm(buffer.copyOfRange(0, length))
            }
            duix?.stopPush()
            inputStream.close()
        }
        thread.start()
    }

    private fun playWAVFile() {
        val thread = Thread {
            val wavName = "1.wav"
            val wavFile = File(mContext.externalCacheDir, wavName)
            if (!wavFile.exists()) {
                val inputStream = assets.open("wav/$wavName")
                if (!mContext.externalCacheDir!!.exists()) mContext.externalCacheDir!!.mkdirs()
                val out = FileOutputStream(wavFile)
                val buffer = ByteArray(1024)
                var length = 0
                while (inputStream.read(buffer).also { length = it } > 0) out.write(buffer, 0, length)
                out.close()
                inputStream.close()
            }
            duix?.playAudio(wavFile.absolutePath)
        }
        thread.start()
    }
}
