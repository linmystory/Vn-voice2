package com.docdoc.app

// ============================================================================
// TtsFileSaverPlugin.kt
//
// Plugin Capacitor native cho Android, dùng để tổng hợp văn bản thành GIỌNG NÓI
// và LƯU RA FILE WAV THẬT trên thiết bị, sử dụng bộ máy TextToSpeech (TTS) có
// sẵn của hệ điều hành Android — hoàn toàn offline, không cần API key hay
// dịch vụ đám mây nào.
//
// PHIÊN BẢN ĐÃ VÁ LỖI (bản "hoàn thiện"):
//   - Báo trạng thái engine rõ ràng (đang tải / sẵn sàng / lỗi) qua
//     getEngineStatus(), thay vì để JS chờ vô thời hạn với thông báo mơ hồ.
//   - Toàn bộ luồng nền được bọc try/catch(Throwable) để một lỗi bất ngờ
//     (kể cả OutOfMemoryError khi ghép file quá dài) không làm crash cả ứng
//     dụng, mà chỉ trả lỗi có kiểm soát về cho JS.
//   - Kiểm tra kết quả setLanguage()/setVoice() để tự động rơi về giọng mặc
//     định khi ngôn ngữ yêu cầu không có sẵn trên máy, thay vì đọc sai giọng
//     mà không báo gì.
//
// ĐÃ THU GỌN: bản này CHỈ HỖ TRỢ ANDROID 10 (API 29) TRỞ LÊN.
// Từ Android 10, việc ghi file vào bộ nhớ công khai dùng MediaStore + scoped
// storage nên:
//   - KHÔNG cần xin quyền WRITE_EXTERNAL_STORAGE lúc chạy.
//   - KHÔNG cần khai báo <provider> FileProvider trong AndroidManifest.xml.
//   - KHÔNG cần script vá manifest (scripts/patch_manifest.py) hay file
//     res/xml/file_paths.xml nữa — cả hai đã được loại khỏi project.
// minSdkVersion của project (android/variables.gradle) phải đặt là 29 để
// Play Store / trình cài đặt tự chặn máy Android cũ hơn.
//
// Vì sao cần plugin native riêng?
// Web Speech API (window.speechSynthesis) chạy trong WebView CHỈ có thể phát
// âm thanh trực tiếp ra loa, không cho phép lấy dữ liệu âm thanh dưới dạng
// file. Ngược lại, Android cung cấp hàm gốc:
//     TextToSpeech.synthesizeToFile(text, params, file, utteranceId)
// hàm này ghi thẳng dữ liệu âm thanh (PCM/WAV) ra file mà KHÔNG phát ra loa.
// Plugin này gọi hàm đó, xử lý việc chia nhỏ văn bản dài (do TTS có giới hạn
// độ dài mỗi lần tổng hợp), rồi ghép nhiều đoạn WAV lại thành một file hoàn
// chỉnh duy nhất.
//
// Cách cài đặt: xem HUONG_DAN_CAI_DAT.md đi kèm trong dự án.
// ============================================================================

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@CapacitorPlugin(name = "TtsFileSaver")
class TtsFileSaverPlugin : Plugin() {

    companion object {
        private const val TAG = "TtsFileSaverPlugin"

        // Trạng thái engine TTS, JS dùng để hiển thị đúng thông báo thay vì
        // đoán mò khi mọi lệnh đều bị reject với cùng 1 câu chung chung.
        private const val STATUS_LOADING = "loading"
        private const val STATUS_READY = "ready"
        private const val STATUS_ERROR = "error"
    }

    private var tts: TextToSpeech? = null

    @Volatile private var engineStatus: String = STATUS_LOADING

    @Volatile private var engineErrorMessage: String = ""

    // executor riêng cho toàn bộ thao tác TTS (đồng bộ hoá truy cập engine),
    // isShutdown/isTerminated được kiểm tra trước khi execute() để tránh
    // RejectedExecutionException sau khi Activity đã bị huỷ.
    private var executor: ExecutorService = Executors.newSingleThreadExecutor()

    // Độ dài an toàn mỗi đoạn văn bản gửi cho TTS (ký tự). Android TTS có giới
    // hạn thực tế do TextToSpeech.getMaxSpeechInputLength() cung cấp, nhưng ta
    // dùng một ngưỡng an toàn nhỏ hơn để tránh lỗi trên các máy/engine khác nhau.
    private val SAFE_CHUNK_LEN = 350

    override fun load() {
        super.load()
        try {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    engineStatus = STATUS_READY
                } else {
                    engineStatus = STATUS_ERROR
                    engineErrorMessage =
                        "Không khởi tạo được bộ máy Text-to-Speech (mã lỗi $status). " +
                        "Máy có thể chưa cài ứng dụng chuyển văn bản thành giọng nói nào."
                    Log.e(TAG, engineErrorMessage)
                }
            }
        } catch (t: Throwable) {
            // Một số thiết bị OEM (ROM tuỳ biến, không có Google TTS/Samsung TTS)
            // có thể ném lỗi ngay khi khởi tạo TextToSpeech thay vì trả status lỗi
            // qua callback. Bắt lại ở đây để tránh crash cả ứng dụng lúc mở app.
            engineStatus = STATUS_ERROR
            engineErrorMessage = "Không thể khởi tạo Text-to-Speech: ${t.message}"
            Log.e(TAG, engineErrorMessage, t)
        }
    }

    // ------------------------------------------------------------------
    // Cho JS hỏi trạng thái engine hiện tại, để hiển thị đúng thông báo
    // (đang tải / sẵn sàng / lỗi kèm lý do) thay vì đoán.
    // ------------------------------------------------------------------
    @PluginMethod
    fun getEngineStatus(call: PluginCall) {
        val ret = JSObject()
        ret.put("status", engineStatus)
        ret.put("message", engineErrorMessage)
        call.resolve(ret)
    }

    private fun ensureReadyOrReject(call: PluginCall): Boolean {
        return when (engineStatus) {
            STATUS_READY -> true
            STATUS_ERROR -> {
                call.reject(
                    engineErrorMessage.ifBlank {
                        "Bộ máy Text-to-Speech gặp lỗi và không sẵn sàng."
                    }
                )
                false
            }
            else -> {
                call.reject("Bộ máy Text-to-Speech đang khởi động, vui lòng thử lại sau vài giây.")
                false
            }
        }
    }

    private fun runOnExecutor(call: PluginCall, task: () -> Unit) {
        if (executor.isShutdown || executor.isTerminated) {
            call.reject("Ứng dụng đang đóng, không thể xử lý yêu cầu này.")
            return
        }
        try {
            executor.execute {
                try {
                    task()
                } catch (t: Throwable) {
                    // Lưới an toàn cuối cùng: bất kỳ lỗi không lường trước nào
                    // (kể cả OutOfMemoryError) đều được bắt lại ở đây để KHÔNG
                    // làm sập tiến trình ứng dụng, chỉ báo lỗi có kiểm soát.
                    Log.e(TAG, "Lỗi không mong muốn trong tác vụ TTS: ${t.message}", t)
                    try {
                        call.reject("Đã xảy ra lỗi không mong muốn: ${t.message}")
                    } catch (_: Throwable) {
                        // call có thể đã được resolve/reject trước đó, bỏ qua.
                    }
                }
            }
        } catch (t: Throwable) {
            call.reject("Không thể lên lịch tác vụ TTS: ${t.message}")
        }
    }

    // ------------------------------------------------------------------
    // Hàm chính: nhận văn bản, tùy chọn giọng/tốc độ/cao độ, trả về Uri file
    // ------------------------------------------------------------------
    @PluginMethod
    fun synthesizeToFile(call: PluginCall) {
        val text = call.getString("text")
        if (text.isNullOrBlank()) {
            call.reject("Thiếu văn bản cần đọc (text).")
            return
        }
        if (!ensureReadyOrReject(call)) return
        // Android 10+ dùng MediaStore/scoped storage => không cần xin quyền
        // lưu trữ lúc chạy, tổng hợp file ngay.
        doSynthesizeToFile(call)
    }

    private fun doSynthesizeToFile(call: PluginCall) {
        val text = call.getString("text")
        if (text.isNullOrBlank()) {
            call.reject("Thiếu văn bản cần đọc (text).")
            return
        }

        val voiceName = call.getString("voiceName") ?: ""
        val lang = call.getString("lang") ?: "vi-VN"
        val rate = (call.getFloat("rate") ?: 1.0f)
        val pitch = (call.getFloat("pitch") ?: 1.0f)

        runOnExecutor(call) {
            var tmpDir: File? = null
            var mergedFile: File? = null
            try {
                val engine = tts
                if (engine == null) {
                    call.reject("Bộ máy Text-to-Speech chưa sẵn sàng. Vui lòng thử lại sau vài giây.")
                    return@runOnExecutor
                }

                applyVoiceSettings(engine, voiceName, lang, rate, pitch)
                val chunks = splitIntoChunks(text, SAFE_CHUNK_LEN)
                if (chunks.isEmpty()) {
                    call.reject("Văn bản rỗng sau khi xử lý.")
                    return@runOnExecutor
                }

                val workDir = File(context.cacheDir, "tts_tmp_${System.currentTimeMillis()}")
                if (!workDir.mkdirs() && !workDir.exists()) {
                    call.reject("Không tạo được thư mục tạm để xử lý âm thanh.")
                    return@runOnExecutor
                }
                tmpDir = workDir

                val chunkFiles = ArrayList<File>()
                var synthesisFailed = false
                var failMessage = ""

                for ((index, chunkText) in chunks.withIndex()) {
                    val chunkFile = File(workDir, "chunk_%04d.wav".format(index))
                    val utteranceId = "chunk_$index"
                    val latch = CountDownLatch(1)
                    var thisChunkFailed = false

                    val listener = object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) {
                            if (id == utteranceId) latch.countDown()
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) {
                            if (id == utteranceId) {
                                thisChunkFailed = true
                                latch.countDown()
                            }
                        }
                        override fun onError(id: String?, errorCode: Int) {
                            if (id == utteranceId) {
                                thisChunkFailed = true
                                latch.countDown()
                            }
                        }
                    }

                    engine.setOnUtteranceProgressListener(listener)

                    val params = Bundle()
                    val result = try {
                        engine.synthesizeToFile(chunkText, params, chunkFile, utteranceId)
                    } catch (t: Throwable) {
                        Log.e(TAG, "synthesizeToFile ném lỗi ở đoạn ${index + 1}: ${t.message}", t)
                        TextToSpeech.ERROR
                    }
                    if (result != TextToSpeech.SUCCESS) {
                        thisChunkFailed = true
                        latch.countDown()
                    }

                    // Chờ tối đa 20 giây cho mỗi đoạn (đoạn đã được giới hạn ngắn nên rất hiếm khi cần lâu vậy)
                    val finished = try {
                        latch.await(20, TimeUnit.SECONDS)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        false
                    }
                    if (!finished || thisChunkFailed || !chunkFile.exists() || chunkFile.length() == 0L) {
                        synthesisFailed = true
                        failMessage = "Không thể tổng hợp đoạn văn bản thứ ${index + 1}/${chunks.size}."
                        break
                    }
                    chunkFiles.add(chunkFile)
                }

                if (synthesisFailed) {
                    call.reject(failMessage)
                    return@runOnExecutor
                }

                // Ghép các file WAV nhỏ thành 1 file WAV hoàn chỉnh
                val outFile = File(context.cacheDir, "tts_output_${System.currentTimeMillis()}.wav")
                mergedFile = outFile
                mergeWavFiles(chunkFiles, outFile)

                // Lưu file vào bộ nhớ công khai (Music/DocDocTTS) qua MediaStore
                // (Android 10+, không cần xin quyền runtime), đồng thời trả về
                // content Uri để JS có thể chia sẻ/mở file.
                val fileName = "doc-van-ban-${System.currentTimeMillis()}.wav"
                val savedUri = saveWavToPublicStorage(outFile, fileName)

                if (savedUri == null) {
                    call.reject("Không thể lưu file âm thanh vào bộ nhớ thiết bị.")
                    return@runOnExecutor
                }

                val ret = JSObject()
                ret.put("uri", savedUri.toString())
                ret.put("fileName", fileName)
                call.resolve(ret)

            } catch (oom: OutOfMemoryError) {
                // Văn bản quá dài / quá nhiều đoạn có thể khiến bước ghép file
                // tốn nhiều bộ nhớ. Bắt riêng OOM để trả lỗi rõ ràng thay vì để
                // tiến trình bị hệ điều hành giết (crash im lặng).
                Log.e(TAG, "Hết bộ nhớ khi tạo file âm thanh", oom)
                call.reject("Văn bản quá dài khiến thiết bị hết bộ nhớ khi xử lý. Vui lòng thử với đoạn văn bản ngắn hơn.")
            } catch (io: IOException) {
                Log.e(TAG, "Lỗi I/O khi tạo file âm thanh: ${io.message}", io)
                call.reject("Lỗi khi ghi file âm thanh: ${io.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi khi tạo file âm thanh: ${e.message}", e)
                call.reject("Lỗi khi tạo file âm thanh: ${e.message}", e)
            } finally {
                // Dọn dẹp file/thư mục tạm nếu còn sót lại do lỗi giữa chừng,
                // tránh rác tích lũy trong cache theo thời gian.
                try { tmpDir?.deleteRecursively() } catch (_: Throwable) {}
                try { mergedFile?.delete() } catch (_: Throwable) {}
            }
        }
    }

    // ------------------------------------------------------------------
    // Lấy danh sách toàn bộ giọng nói có sẵn trên máy (dùng để đổ vào dropdown)
    // ------------------------------------------------------------------
    @PluginMethod
    fun getVoices(call: PluginCall) {
        if (!ensureReadyOrReject(call)) return
        try {
            val engine = tts
            if (engine == null) {
                call.reject("Bộ máy Text-to-Speech chưa sẵn sàng. Vui lòng thử lại sau vài giây.")
                return
            }
            val arr = JSArray()
            engine.voices?.forEach { v ->
                val obj = JSObject()
                obj.put("name", v.name)
                obj.put("lang", v.locale.toLanguageTag())
                arr.put(obj)
            }
            val ret = JSObject()
            ret.put("voices", arr)
            call.resolve(ret)
        } catch (t: Throwable) {
            Log.e(TAG, "Lỗi khi lấy danh sách giọng đọc: ${t.message}", t)
            call.reject("Không lấy được danh sách giọng đọc: ${t.message}")
        }
    }

    // ------------------------------------------------------------------
    // Đọc văn bản ra loa TRỰC TIẾP bằng TextToSpeech gốc của Android
    // (dùng thay cho Web Speech API vì WebView có thể không hỗ trợ đầy đủ)
    // ------------------------------------------------------------------
    @Volatile private var speakCancelled = false
    // Giữ tham chiếu tới latch đang chờ, để stopSpeaking() có thể giải phóng
    // ngay lập tức thay vì để executor bị kẹt tới khi hết hạn chờ (fix bug:
    // tts.stop() không đảm bảo callback onDone/onError sẽ được gọi).
    @Volatile private var currentLatch: CountDownLatch? = null

    @PluginMethod
    fun speak(call: PluginCall) {
        val text = call.getString("text")
        if (text.isNullOrBlank()) {
            call.reject("Thiếu văn bản cần đọc (text).")
            return
        }
        if (!ensureReadyOrReject(call)) return

        val voiceName = call.getString("voiceName") ?: ""
        val lang = call.getString("lang") ?: "vi-VN"
        val rate = (call.getFloat("rate") ?: 1.0f)
        val pitch = (call.getFloat("pitch") ?: 1.0f)

        runOnExecutor(call) {
            val engine = tts
            if (engine == null) {
                call.reject("Bộ máy Text-to-Speech chưa sẵn sàng. Vui lòng thử lại sau vài giây.")
                return@runOnExecutor
            }
            try {
                speakCancelled = false
                applyVoiceSettings(engine, voiceName, lang, rate, pitch)
                val chunks = splitIntoChunks(text, SAFE_CHUNK_LEN)
                if (chunks.isEmpty()) {
                    call.reject("Văn bản rỗng sau khi xử lý.")
                    return@runOnExecutor
                }

                for ((index, chunkText) in chunks.withIndex()) {
                    if (speakCancelled) break
                    val utteranceId = "speak_chunk_$index"
                    val latch = CountDownLatch(1)
                    currentLatch = latch
                    var errored = false

                    val listener = object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) {
                            if (id == utteranceId) latch.countDown()
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) {
                            if (id == utteranceId) { errored = true; latch.countDown() }
                        }
                        override fun onError(id: String?, errorCode: Int) {
                            if (id == utteranceId) { errored = true; latch.countDown() }
                        }
                    }
                    engine.setOnUtteranceProgressListener(listener)

                    val params = Bundle()
                    val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    val result = try {
                        engine.speak(chunkText, queueMode, params, utteranceId)
                    } catch (t: Throwable) {
                        Log.e(TAG, "speak() ném lỗi ở đoạn ${index + 1}: ${t.message}", t)
                        TextToSpeech.ERROR
                    }
                    if (result != TextToSpeech.SUCCESS) {
                        errored = true
                        latch.countDown()
                    }

                    try {
                        latch.await(30, TimeUnit.SECONDS)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    currentLatch = null
                    if (speakCancelled || errored) break
                }
                call.resolve()
            } catch (t: Throwable) {
                Log.e(TAG, "Lỗi khi đọc văn bản: ${t.message}", t)
                call.reject("Lỗi khi đọc văn bản: ${t.message}")
            }
        }
    }

    @PluginMethod
    fun stopSpeaking(call: PluginCall) {
        try {
            speakCancelled = true
            tts?.stop()
            // Giải phóng ngay thread đang chờ trong speak(), tránh việc lệnh đọc
            // tiếp theo (xếp hàng sau trên cùng 1 executor) phải chờ tới 30 giây.
            currentLatch?.countDown()
            call.resolve()
        } catch (t: Throwable) {
            // Dừng đọc không phải là thao tác quan trọng tới mức phải làm app
            // crash nếu có lỗi lạ; báo lỗi nhẹ nhàng cho JS là đủ.
            Log.e(TAG, "Lỗi khi dừng đọc: ${t.message}", t)
            call.reject("Lỗi khi dừng đọc: ${t.message}")
        }
    }

    // ------------------------------------------------------------------
    // Mở hộp thoại Chia sẻ / Lưu của Android cho file vừa tạo
    // ------------------------------------------------------------------
    @PluginMethod
    fun shareFile(call: PluginCall) {
        val uriString = call.getString("uri")
        if (uriString.isNullOrBlank()) {
            call.reject("Thiếu đường dẫn file (uri).")
            return
        }
        try {
            val uri = Uri.parse(uriString)
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "audio/wav"
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val chooser = Intent.createChooser(intent, "Lưu / Chia sẻ file âm thanh")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            call.resolve()
        } catch (t: Throwable) {
            Log.e(TAG, "Không thể mở hộp thoại chia sẻ: ${t.message}", t)
            call.reject("Không thể mở hộp thoại chia sẻ: ${t.message}")
        }
    }

    // ------------------------------------------------------------------
    // Chọn giọng nói + áp dụng tốc độ/cao độ cho TTS engine.
    // Nếu ngôn ngữ yêu cầu không có sẵn trên máy, tự rơi về giọng mặc định
    // của engine thay vì im lặng đọc sai giọng (hoặc đọc lỗi) mà không báo.
    // ------------------------------------------------------------------
    private fun applyVoiceSettings(engine: TextToSpeech, voiceName: String, lang: String, rate: Float, pitch: Float) {
        var chosenVoice: Voice? = null

        if (voiceName.isNotBlank()) {
            chosenVoice = try {
                engine.voices?.firstOrNull { it.name == voiceName }
            } catch (t: Throwable) {
                null
            }
        }
        if (chosenVoice == null && lang.isNotBlank()) {
            val locale = parseLocale(lang)
            chosenVoice = try {
                engine.voices?.firstOrNull { it.locale.language == locale.language }
            } catch (t: Throwable) {
                null
            }
        }

        if (chosenVoice != null) {
            val voiceResult = try {
                engine.setVoice(chosenVoice)
                TextToSpeech.SUCCESS
            } catch (t: Throwable) {
                Log.w(TAG, "setVoice thất bại: ${t.message}")
                TextToSpeech.ERROR
            }
            if (voiceResult != TextToSpeech.SUCCESS) {
                applyLanguageFallback(engine, lang)
            }
        } else if (lang.isNotBlank()) {
            applyLanguageFallback(engine, lang)
        }

        try {
            engine.setSpeechRate(rate.coerceIn(0.1f, 4.0f))
            engine.setPitch(pitch.coerceIn(0.1f, 2.0f))
        } catch (t: Throwable) {
            Log.w(TAG, "Không áp dụng được tốc độ/cao độ giọng đọc: ${t.message}")
        }
    }

    // Đặt ngôn ngữ, kiểm tra kết quả trả về. Nếu ngôn ngữ không được engine
    // hỗ trợ (LANG_MISSING_DATA hoặc LANG_NOT_SUPPORTED), rơi về tiếng Anh
    // (thường có sẵn trên mọi máy Android) thay vì để engine ở trạng thái
    // không xác định.
    private fun applyLanguageFallback(engine: TextToSpeech, lang: String) {
        val locale = parseLocale(lang)
        val result = try {
            engine.setLanguage(locale)
        } catch (t: Throwable) {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Ngôn ngữ '$lang' không được hỗ trợ (mã $result), chuyển sang tiếng Anh mặc định.")
            try {
                engine.setLanguage(Locale.US)
            } catch (t: Throwable) {
                // Nếu cả tiếng Anh cũng lỗi thì để engine dùng ngôn ngữ hiện tại,
                // vẫn tốt hơn là ném lỗi làm hỏng toàn bộ tác vụ đọc/lưu file.
                Log.w(TAG, "Không đặt được cả ngôn ngữ dự phòng: ${t.message}")
            }
        }
    }

    private fun parseLocale(bcp47: String): Locale {
        return try {
            val locale = Locale.forLanguageTag(bcp47)
            if (locale.language.isBlank()) Locale("vi", "VN") else locale
        } catch (e: Exception) {
            Locale("vi", "VN")
        }
    }

    // ------------------------------------------------------------------
    // Tách văn bản dài thành các đoạn ngắn theo câu, an toàn cho TTS
    // ------------------------------------------------------------------
    private fun splitIntoChunks(text: String, maxLen: Int): List<String> {
        val normalized = text.replace("\r\n", "\n").trim()
        if (normalized.isEmpty()) return emptyList()

        val sentenceRegex = Regex("(?<=[.!?…])\\s+|\\n+")
        val rawSentences = normalized.split(sentenceRegex).map { it.trim() }.filter { it.isNotEmpty() }

        val chunks = ArrayList<String>()
        var buffer = StringBuilder()

        fun flushBuffer() {
            if (buffer.isNotEmpty()) {
                chunks.add(buffer.toString())
                buffer = StringBuilder()
            }
        }

        for (sentence in rawSentences) {
            if (sentence.length > maxLen) {
                flushBuffer()
                var remaining = sentence
                while (remaining.length > maxLen) {
                    var cut = remaining.lastIndexOf(' ', maxLen)
                    if (cut <= 0) cut = maxLen
                    chunks.add(remaining.substring(0, cut).trim())
                    remaining = remaining.substring(cut).trim()
                }
                if (remaining.isNotEmpty()) buffer.append(remaining)
                continue
            }

            val candidateLen = buffer.length + 1 + sentence.length
            if (candidateLen > maxLen) {
                flushBuffer()
                buffer.append(sentence)
            } else {
                if (buffer.isNotEmpty()) buffer.append(' ')
                buffer.append(sentence)
            }
        }
        flushBuffer()
        return chunks
    }

    // ------------------------------------------------------------------
    // Đọc header của 1 file WAV bằng cách quét thực sự các chunk theo chuẩn
    // RIFF/WAVE, thay vì giả định "data" luôn nằm cố định ở byte 44.
    //
    // LÝ DO: không phải engine TTS nào cũng xuất chunk "fmt " đúng 16 byte.
    // Một số máy (đặc biệt Samsung TTS, một số bản Google TTS) xuất định dạng
    // WAVE_FORMAT_EXTENSIBLE với chunk "fmt " dài 18 hoặc 40 byte, hoặc chèn
    // thêm chunk "fact"/"LIST" trước "data". Nếu cứ giả định offset 44 cố định
    // như bản gốc, phần đầu dữ liệu âm thanh thật sẽ bị cắt nhầm vào giữa chunk
    // khác → file ghép ra bị nhiễu/rè hoặc im lặng, mà KHÔNG hề báo lỗi gì.
    // ------------------------------------------------------------------
    private data class WavInfo(
        val dataOffset: Long,
        val dataSize: Long,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val byteRate: Int,
        val blockAlign: Int
    )

    private fun readWavInfo(file: File): WavInfo {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (length < 12) throw IOException("File WAV không hợp lệ (quá ngắn): ${file.name}")

            val riffHeader = ByteArray(12)
            raf.readFully(riffHeader)
            val riffTag = String(riffHeader, 0, 4, Charsets.US_ASCII)
            val waveTag = String(riffHeader, 8, 4, Charsets.US_ASCII)
            if (riffTag != "RIFF" || waveTag != "WAVE") {
                throw IOException("File không đúng định dạng WAV/RIFF: ${file.name}")
            }

            var sampleRate = 22050
            var channels = 1
            var bitsPerSample = 16
            var byteRate = sampleRate * channels * bitsPerSample / 8
            var blockAlign = channels * bitsPerSample / 8
            var dataOffset = -1L
            var dataSize = -1L

            while (raf.filePointer + 8 <= length) {
                val chunkHeader = ByteArray(8)
                raf.readFully(chunkHeader)
                val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                var chunkSize = readInt32LE(chunkHeader, 4).toLong() and 0xFFFFFFFFL
                // Phòng file bị ghi thiếu/hỏng phần cuối: không cho chunkSize vượt quá phần còn lại
                val remaining = length - raf.filePointer
                if (chunkSize > remaining) chunkSize = remaining

                when (chunkId) {
                    "fmt " -> {
                        val fmt = ByteArray(chunkSize.toInt())
                        raf.readFully(fmt)
                        if (fmt.size >= 16) {
                            channels = readInt16LE(fmt, 2)
                            sampleRate = readInt32LE(fmt, 4)
                            byteRate = readInt32LE(fmt, 8)
                            blockAlign = readInt16LE(fmt, 12)
                            bitsPerSample = readInt16LE(fmt, 14)
                        }
                    }
                    "data" -> {
                        dataOffset = raf.filePointer
                        dataSize = chunkSize
                        raf.seek(raf.filePointer + chunkSize)
                    }
                    else -> {
                        raf.seek(raf.filePointer + chunkSize)
                    }
                }
                // Các chunk RIFF luôn được đệm cho chẵn byte
                if (chunkSize % 2L == 1L && raf.filePointer < length) {
                    raf.seek(raf.filePointer + 1)
                }
            }

            if (dataOffset < 0 || dataSize <= 0) {
                throw IOException("Không tìm thấy dữ liệu âm thanh hợp lệ trong file: ${file.name}")
            }
            return WavInfo(dataOffset, dataSize, sampleRate, channels, bitsPerSample, byteRate, blockAlign)
        }
    }

    // ------------------------------------------------------------------
    // Ghép nhiều file WAV nhỏ thành 1 file WAV hoàn chỉnh (dùng chung định dạng
    // âm thanh của file đầu tiên; chỉ ghi phần dữ liệu PCM thật của mỗi file,
    // xác định đúng vị trí nhờ readWavInfo ở trên thay vì offset cố định).
    // Đọc/ghi theo luồng bằng bộ đệm cố định 8KB để tránh nạp cả file vào RAM
    // (an toàn hơn với văn bản dài, giảm nguy cơ OutOfMemoryError).
    // ------------------------------------------------------------------
    private fun mergeWavFiles(files: List<File>, outFile: File) {
        if (files.isEmpty()) throw IOException("Không có đoạn âm thanh nào để ghép.")

        val infos = files.map { readWavInfo(it) }
        val first = infos[0]
        val totalDataSize = infos.sumOf { it.dataSize }

        java.io.BufferedOutputStream(java.io.FileOutputStream(outFile)).use { out ->
            writeWavHeader(out, totalDataSize, first.sampleRate, first.channels, first.bitsPerSample, first.byteRate, first.blockAlign)
            files.forEachIndexed { i, f ->
                val info = infos[i]
                RandomAccessFile(f, "r").use { raf ->
                    raf.seek(info.dataOffset)
                    var remaining = info.dataSize
                    val buffer = ByteArray(8192)
                    while (remaining > 0) {
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val n = raf.read(buffer, 0, toRead)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        remaining -= n
                    }
                }
            }
        }
    }

    private fun writeWavHeader(
        out: java.io.OutputStream,
        dataSize: Long,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        byteRate: Int,
        blockAlign: Int
    ) {
        // WAV chuẩn (RIFF) dùng trường 32-bit cho kích thước, tức tối đa ~4GB.
        // Với văn bản 25.000 ký tự ở tốc độ đọc bình thường, dữ liệu PCM sinh
        // ra không thể chạm ngưỡng này, nhưng vẫn chặn tường minh để báo lỗi
        // rõ ràng thay vì ghi ra 1 file WAV hỏng (giá trị âm/tràn số) nếu có
        // trường hợp bất thường nào đó phát sinh văn bản khổng lồ.
        if (dataSize > 0xFFFFFFFFL - 36L) {
            throw IOException("Dữ liệu âm thanh quá lớn để ghi thành 1 file WAV (vượt giới hạn 4GB).")
        }

        val totalDataLen = dataSize + 36
        val header = ByteArray(44)

        writeAscii(header, 0, "RIFF")
        writeInt32LE(header, 4, totalDataLen.toInt())
        writeAscii(header, 8, "WAVE")
        writeAscii(header, 12, "fmt ")
        writeInt32LE(header, 16, 16) // Subchunk1Size cho PCM
        writeInt16LE(header, 20, 1)  // AudioFormat = 1 (PCM)
        writeInt16LE(header, 22, channels)
        writeInt32LE(header, 24, sampleRate)
        writeInt32LE(header, 28, byteRate)
        writeInt16LE(header, 32, blockAlign)
        writeInt16LE(header, 34, bitsPerSample)
        writeAscii(header, 36, "data")
        writeInt32LE(header, 40, dataSize.toInt())

        out.write(header)
    }

    private fun readInt16LE(b: ByteArray, offset: Int): Int {
        return (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readInt32LE(b: ByteArray, offset: Int): Int {
        return (b[offset].toInt() and 0xFF) or
                ((b[offset + 1].toInt() and 0xFF) shl 8) or
                ((b[offset + 2].toInt() and 0xFF) shl 16) or
                ((b[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun writeInt16LE(b: ByteArray, offset: Int, value: Int) {
        b[offset] = (value and 0xFF).toByte()
        b[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun writeInt32LE(b: ByteArray, offset: Int, value: Int) {
        b[offset] = (value and 0xFF).toByte()
        b[offset + 1] = ((value shr 8) and 0xFF).toByte()
        b[offset + 2] = ((value shr 16) and 0xFF).toByte()
        b[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeAscii(b: ByteArray, offset: Int, text: String) {
        val bytes = text.toByteArray(Charsets.US_ASCII)
        System.arraycopy(bytes, 0, b, offset, bytes.size)
    }

    // ------------------------------------------------------------------
    // Lưu file WAV vào bộ nhớ công khai của thiết bị (Music/DocDocTTS)
    // Dùng MediaStore (Android 10+, không cần xin quyền runtime).
    // ------------------------------------------------------------------
    private fun saveWavToPublicStorage(sourceFile: File, displayName: String): Uri? {
        val resolver = context.contentResolver

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/DocDocTTS")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri = resolver.insert(collection, values) ?: return null

        var writeOk = false
        try {
            resolver.openOutputStream(itemUri)?.use { out ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(out)
                }
                writeOk = true
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Lỗi khi ghi dữ liệu vào MediaStore: ${t.message}", t)
        }

        if (!writeOk) {
            // Ghi thất bại giữa chừng: xoá bản ghi "pending" rác thay vì để lại
            // 1 file 0-byte hiển thị trong Music của người dùng.
            try { resolver.delete(itemUri, null, null) } catch (_: Throwable) {}
            return null
        }

        values.clear()
        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
        try {
            resolver.update(itemUri, values, null, null)
        } catch (t: Throwable) {
            Log.e(TAG, "Lỗi khi hoàn tất bản ghi MediaStore: ${t.message}", t)
            return null
        }
        return itemUri
    }

    override fun handleOnDestroy() {
        super.handleOnDestroy()
        try { tts?.stop() } catch (_: Throwable) {}
        try { tts?.shutdown() } catch (_: Throwable) {}
        try { executor.shutdownNow() } catch (_: Throwable) {}
    }
}
