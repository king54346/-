package ai.guiji.duix.test.net

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object DashScopeService {

    private const val API_KEY = "xxxxxxxx"
    private const val BASE_URL = "https://dashscope.aliyuncs.com/api/v1"
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    // HTTP 客户端（LLM / TTS 用）
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // WebSocket 客户端（ASR 用），readTimeout=0 表示不限制读超时
    private val wsClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    // ─────────────────────────────────────────────────────────
    // Step 1: PCM 文件 → 识别文字（qwen3-asr-flash-realtime，WebSocket）
    // ─────────────────────────────────────────────────────────
    fun recognize(pcmPath: String, onResult: (text: String?) -> Unit) {
        Thread {
            try {
                val latch     = CountDownLatch(1)
                val resultRef = AtomicReference<String?>(null)
                val pcmData   = File(pcmPath).readBytes()

                val wsRequest = Request.Builder()
                    .url("wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=qwen3-asr-flash-realtime")
                    .header("Authorization", "Bearer $API_KEY")
                    .build()

                wsClient.newWebSocket(wsRequest, object : WebSocketListener() {

                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        android.util.Log.d("DashScope-ASR", "WebSocket 已连接")

                        // 1. 发送会话配置（对应 Java 示例的 updateSession）
                        val sessionUpdate = JSONObject().apply {
                            put("type", "session.update")
                            put("session", JSONObject().apply {
                                put("modalities", JSONArray().put("text"))
                                put("input_audio_format", "pcm")
                                put("input_audio_sample_rate", 16000)
                                put("input_audio_transcription", JSONObject().apply {
                                    put("language", "zh")
                                })
                            })
                        }
                        webSocket.send(sessionUpdate.toString())

                        // 2. 后台线程分块发送 PCM 数据（对应 appendAudio 循环）
                        Thread {
                            val chunkSize = 1024
                            var offset = 0
                            while (offset < pcmData.size) {
                                val end   = minOf(offset + chunkSize, pcmData.size)
                                val chunk = pcmData.copyOfRange(offset, end)
                                val b64   = Base64.encodeToString(chunk, Base64.NO_WRAP)
                                webSocket.send(
                                    JSONObject()
                                        .put("type", "input_audio_buffer.append")
                                        .put("audio", b64)
                                        .toString()
                                )
                                offset += chunkSize
                                Thread.sleep(30) // 模拟实时流速（30ms/1024字节 ≈ 16kHz实时）
                            }
                            // 3. 提交音频缓冲，触发服务端转录（对应 endSession）
                            webSocket.send(
                                JSONObject().put("type", "input_audio_buffer.commit").toString()
                            )
                            android.util.Log.d("DashScope-ASR", "音频发送完毕，已提交")
                        }.start()
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        android.util.Log.d("DashScope-ASR", "收到: $text")
                        try {
                            val json = JSONObject(text)
                            when (json.optString("type")) {
                                // 最终转录结果
                                "conversation.item.input_audio_transcription.completed" -> {
                                    val transcript = json.optString("transcript", "").trim()
                                    android.util.Log.d("DashScope-ASR", "转录结果: $transcript")
                                    resultRef.set(transcript)
                                    latch.countDown()
                                    webSocket.close(1000, "done")
                                }
                                // 中间流式文字（可忽略，也可用于实时显示）
                                "conversation.item.input_audio_transcription.text" -> {
                                    android.util.Log.d("DashScope-ASR", "流式文字: ${json.optString("text")}")
                                }
                                // 错误事件
                                "error" -> {
                                    android.util.Log.e("DashScope-ASR", "服务端错误: $text")
                                    latch.countDown()
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        android.util.Log.d("DashScope-ASR", "WebSocket 关闭: $code $reason")
                        latch.countDown()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        android.util.Log.e("DashScope-ASR", "WebSocket 失败: ${t.message}")
                        t.printStackTrace()
                        latch.countDown()
                    }
                })

                // 等待转录结果，最多 30 秒
                latch.await(30, TimeUnit.SECONDS)
                onResult(resultRef.get())
            } catch (e: Exception) {
                e.printStackTrace()
                onResult(null)
            }
        }.start()
    }

    // ─────────────────────────────────────────────────────────
    // Step 2: 用户文字 → 大模型回答（qwen-turbo）
    // ─────────────────────────────────────────────────────────
    fun chat(userText: String, onResult: (reply: String?) -> Unit) {
        Thread {
            try {
                val body = JSONObject().apply {
                    put("model", "qwen-turbo")
                    put("input", JSONObject().apply {
                        put("messages", JSONArray().apply {
                            put(JSONObject()
                                .put("role", "system")
                                .put("content", "你是一个友好的AI数字人助手，请用简洁自然的口语回答用户问题，回答不超过100字。"))
                            put(JSONObject()
                                .put("role", "user")
                                .put("content", userText))
                        })
                    })
                }
                val req = Request.Builder()
                    .url("$BASE_URL/services/aigc/text-generation/generation")
                    .header("Authorization", "Bearer $API_KEY")
                    .post(body.toString().toRequestBody(JSON_TYPE))
                    .build()

                val resp = client.newCall(req).execute()
                val reply = JSONObject(resp.body!!.string())
                    .getJSONObject("output")
                    .getString("text")
                    .trim()
                onResult(reply)
            } catch (e: Exception) {
                e.printStackTrace()
                onResult(null)
            }
        }.start()
    }

    // ─────────────────────────────────────────────────────────
    // Step 3: 文字 → TTS PCM 流（qwen3-tts-flash，SSE 流式）
    //   chunk.output.audio.data = base64(24kHz/16bit/单声道 PCM)
    //   实时 resample 24kHz → 16kHz 后推给 DUIX
    // ─────────────────────────────────────────────────────────
    fun tts(
        text: String,
        onStart: () -> Unit,
        onPcmChunk: (ByteArray) -> Unit,
        onEnd: () -> Unit,
        onError: (String) -> Unit,
    ) {
        Thread {
            try {
                val body = JSONObject().apply {
                    put("model", "qwen3-tts-flash")
                    put("input", JSONObject().apply {
                        put("text", text)
                    })
                    put("parameters", JSONObject().apply {
                        put("voice", "Cherry")
                        put("language_type", "Chinese")
                    })
                }
                val req = Request.Builder()
                    .url("$BASE_URL/services/aigc/multimodal-generation/generation")
                    .header("Authorization", "Bearer $API_KEY")
                    .header("X-DashScope-SSE", "enable")
                    .post(body.toString().toRequestBody(JSON_TYPE))
                    .build()

                val resp = client.newCall(req).execute()
                if (resp.header("Content-Type")?.contains("application/json") == true) {
                    val errBody = resp.body!!.string()
                    android.util.Log.e("DashScope-TTS", "error: $errBody")
                    val json = JSONObject(errBody)
                    throw RuntimeException("TTS 错误 [${json.optString("code")}]: ${json.optString("message")}")
                }

                val source  = resp.body!!.source()
                var started = false

                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    runCatching {
                        val json      = JSONObject(data)
                        val audioData = json.getJSONObject("output")
                            .getJSONObject("audio")
                            .optString("data", "")
                        if (audioData.isNotEmpty()) {
                            if (!started) { started = true; onStart() }
                            // base64 → 24kHz PCM → resample → 16kHz PCM
                            val pcm24k = Base64.decode(audioData, Base64.DEFAULT)
                            onPcmChunk(resample24kTo16k(pcm24k))
                        }
                    }
                }
                onEnd()
            } catch (e: Exception) {
                e.printStackTrace()
                onError(e.message ?: "TTS 请求失败")
            }
        }.start()
    }

    // 24kHz/16bit/单声道 → 16kHz/16bit/单声道（线性插值）
    // 比例固定 3:2，每 3 个输入样本产生 2 个输出样本
    private fun resample24kTo16k(pcm24k: ByteArray): ByteArray {
        val inLen  = pcm24k.size / 2          // 输入样本数
        val outLen = inLen * 2 / 3            // 输出样本数（向下取整）
        val result = ByteArray(outLen * 2)

        for (i in 0 until outLen) {
            val srcPos = i * 1.5              // 24000 / 16000 = 1.5
            val lo     = srcPos.toInt().coerceIn(0, inLen - 1)
            val hi     = (lo + 1).coerceIn(0, inLen - 1)
            val frac   = srcPos - lo

            val sLo = ((pcm24k[lo * 2].toInt() and 0xFF) or (pcm24k[lo * 2 + 1].toInt() shl 8)).toShort()
            val sHi = ((pcm24k[hi * 2].toInt() and 0xFF) or (pcm24k[hi * 2 + 1].toInt() shl 8)).toShort()
            val out  = (sLo * (1.0 - frac) + sHi * frac).toInt().toShort()

            result[i * 2]     = (out.toInt() and 0xFF).toByte()
            result[i * 2 + 1] = (out.toInt() ushr 8 and 0xFF).toByte()
        }
        return result
    }

}
