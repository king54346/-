package ai.guiji.duix.test.ui.activity

import ai.guiji.duix.sdk.client.BuildConfig
import ai.guiji.duix.test.databinding.ActivityMainBinding
import ai.guiji.duix.test.ui.dialog.LoadingDialog
import ai.guiji.duix.test.util.LocalModelInstaller
import android.content.Intent
import android.os.Bundle
import android.widget.Toast


class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private var mLoadingDialog: LoadingDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvSdkVersion.text = "SDK Version: ${BuildConfig.VERSION_NAME}"

        prepareAndStart()
    }

    private fun prepareAndStart() {
        if (LocalModelInstaller.isReady(mContext)) {
            jumpToCallActivity()
            return
        }

        mLoadingDialog = LoadingDialog(mContext, "正在准备模型...")
        mLoadingDialog?.show()

        LocalModelInstaller.install(
            context = mContext,
            onProgress = { msg ->
                runOnUiThread { mLoadingDialog?.setContent(msg) }
            },
            onDone = {
                runOnUiThread {
                    mLoadingDialog?.dismiss()
                    jumpToCallActivity()
                }
            },
            onError = { err ->
                runOnUiThread {
                    mLoadingDialog?.dismiss()
                    Toast.makeText(mContext, "准备失败: $err", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun jumpToCallActivity() {
        val intent = Intent(mContext, CallActivity::class.java)
        intent.putExtra("modelUrl", LocalModelInstaller.MODEL_NAME)
        intent.putExtra("debug", false)
        startActivity(intent)
    }
}
