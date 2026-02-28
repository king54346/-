package ai.guiji.duix.test.util

import ai.guiji.duix.sdk.client.VirtualModelUtil
import android.content.Context
import java.io.File
import java.io.FileOutputStream

object LocalModelInstaller {

    const val MODEL_NAME = "bendi3_20240518"
    private const val BASE_CONFIG_NAME = "gj_dh_res"

    fun isReady(context: Context): Boolean {
        return VirtualModelUtil.checkBaseConfig(context) &&
               VirtualModelUtil.checkModel(context, MODEL_NAME)
    }

    fun install(
        context: Context,
        onProgress: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ) {
        Thread {
            try {
                val modelRootDir = "${context.getExternalFilesDir("duix")!!.absolutePath}/model"

                if (!VirtualModelUtil.checkBaseConfig(context)) {
                    onProgress("正在解压基础配置...")
                    extractAsset(context, "models/gj_dh_res.zip", modelRootDir, BASE_CONFIG_NAME)
                }

                if (!VirtualModelUtil.checkModel(context, MODEL_NAME)) {
                    onProgress("正在解压模型文件，请稍候...")
                    extractAsset(context, "models/$MODEL_NAME.zip", modelRootDir, MODEL_NAME)
                }

                onDone()
            } catch (e: Exception) {
                onError(e.message ?: "安装失败")
            }
        }.start()
    }

    private fun extractAsset(
        context: Context,
        assetPath: String,
        modelRootDir: String,
        dirName: String,
    ) {
        // 1. 将 assets 里的 zip 写到缓存目录（ZipUtil 需要 File 路径）
        val cacheZip = File(context.cacheDir, "$dirName.zip")
        context.assets.open(assetPath).use { input ->
            FileOutputStream(cacheZip).use { output ->
                input.copyTo(output)
            }
        }

        // 2. 解压到 model 根目录（zip 内顶层目录名需与 dirName 一致）
        val outDir = File(modelRootDir)
        if (!outDir.exists()) outDir.mkdirs()

        val success = ZipUtil.unzip(cacheZip.absolutePath, outDir.absolutePath, null)
        if (!success) {
            cacheZip.delete()
            throw RuntimeException("解压 $dirName 失败，请检查 assets/models/ 目录下的 zip 文件")
        }

        // 3. 创建 tag 目录（SDK 用它判断模型是否已就绪）
        val tagDir = File("$modelRootDir/tmp/$dirName")
        if (!tagDir.exists()) tagDir.mkdirs()

        // 4. 清理临时 zip
        cacheZip.delete()
    }
}
