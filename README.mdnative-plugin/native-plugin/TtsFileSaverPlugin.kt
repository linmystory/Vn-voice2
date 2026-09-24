package com.docdoc.app

// ============================================================================
// TtsFileSaverPlugin.kt
//
// Plugin Capacitor native cho Android, dùng để tổng hợp văn bản thành GIỌNG NÓI
// và LƯU RA FILE WAV THẬT trên thiết bị, sử dụng bộ máy TextToSpeech (TTS) có
// sẵn của hệ điều hành Android — hoàn toàn offline, không cần API key hay
// dịch vụ đám mây nào.
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

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.core.content.FileProvider
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.PermissionState
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Trên Android 9 trở xuống (API <= 28), việc lưu file vào bộ nhớ công khai
// (Music/DocDocTTS) qua File API truyền thống cần quyền WRITE_EXTERNAL_STORAGE
// được cấp lúc chạy (runtime permission), khai báo trong AndroidManifest.xml
// là chưa đủ. Từ Android 10 (API 29) trở lên ta dùng MediaStore + scoped storage
// nên KHÔNG cần quyền này — Capacitor sẽ chỉ hỏi quyền khi thật sự cần.
private const val STORAGE_PERMISSION_ALIAS = "storage"

@CapacitorPlugin(
    name = "TtsFileSaver",
    permissions = [
        Permission(strings = [Manifest.permission.WRITE_EXTERNAL_STORAGE], alias = STORAGE_PERMISSION_ALIAS)
    ]
)
class TtsFileSaverPlugin : Plugin() {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val executor = Executors.newSingleThreadExecutor()

    // Độ dài an toàn mỗi đoạn văn bản gửi cho TTS (ký tự). Android TTS có giới
    // hạn thực tế do TextToSpeech.getMaxSpeechInputLength() cung cấp, nhưng ta
    // dùng một ngưỡng an toàn nhỏ hơn để tránh lỗi trên các máy/engine khác nhau.
    private val SAFE_CHUNK_LEN = 350

    override fun load() {
        super.load()
        tts = TextToSpeech(context) { status ->
            ttsReady = (status == TextToSpeech.SUCCESS)
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
        if (!ttsReady || tts == null) {
            call.reject("Bộ máy Text-to-Speech chưa sẵn sàng. Vui lòng thử lại sau vài giây.")
            return
        }

        // Chỉ Android 9 trở xuống mới cần xin quyền lưu trữ lúc chạy; Android 10+
        // dùng MediaStore (scoped storage) nên bỏ qua bước này để tránh hỏi quyền thừa.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            getPermissionState(STORAGE_PERMISSION_ALIAS) != PermissionState.GRANTED
        ) {
            requestPermissionForAlias(STORAGE_PERMISSION_ALIAS, call, "storagePermissionCallback")
            return
        }

        doSynthesizeToFile(call)
    }

    @PermissionCallback
    private fun storagePermissionCallback(call: PluginCall) {
        if (getPermissionState(STORAGE_PERMISSION_ALIAS) == PermissionState.GRANTED) {
            doSynthesizeToFile(call)
        } else {
            call.reject("Cần cấp quyền lưu trữ (Bộ nhớ) để lưu file âm thanh trên thiết bị Android 9 trở xuống. Vào Cài đặt > Ứng dụng > Đọc Văn Bản > Quyền để bật thủ công.")
        }
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

        executor.execute {
            var tmpDir: File? = null
            var mergedFile: File? = null
            try {
                applyVoiceSettings(voiceName, lang, rate, pitch)
                val chunks = splitIntoChunks(text, SAFE_CHUNK_LEN)
                if (chunks.isEmpty()) {
                    call.reject("Văn bản rỗng sau khi xử lý.")
                    return@execute
                }

                val workDir = File(context.cacheDir, "tts_tmp_${System.currentTimeMillis()}")
                workDir.mkdirs()
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

                    tts!!.setOnUtteranceProgressListener(listener)

                    val params = Bundle()
                    val result = tts!!.synthesizeToFile(chunkText, params, chunkFile, utteranceId)
                    if (result != TextToSpeech.SUCCESS) {
                        thisChunkFailed = true
                        latch.countDown()
                    }

                    // Chờ tối đa 20 giây cho mỗi đoạn (đoạn đã được giới hạn ngắn nên rất hiếm khi cần lâu vậy)
                    val finished = latch.await(20, TimeUnit.SECONDS)
                    if (!finished || thisChunkFailed || !chunkFile.exists() || chunkFile.length() == 0L) {
                        synthesisFailed = true
                        failMessage = "Không thể tổng hợp đoạn văn bản thứ ${index + 1}/${chunks.size}."
                        break
                    }
                    chunkFiles.add(chunkFile)
                }

                if (synthesisFailed) {
                    workDir.deleteRecursively()
                    call.reject(failMessage)
                    return@execute
                }

                // Ghép các file WAV nhỏ thành 1 file WAV hoàn chỉnh
                val outFile = File(context.cacheDir, "tts_output_${System.currentTimeMillis()}.wav")
                mergedFile = outFile
                mergeWavFiles(chunkFiles, outFile)
                workDir.deleteRecursively()

                // Lưu file vào bộ nhớ công khai (Music/DocDocTTS) để người dùng dễ tìm lại,
                // đồng thời trả về content Uri để JS có thể chia sẻ/mở file.
                val fileName = "doc-van-ban-${System.currentTimeMillis()}.wav"
                val savedUri = saveWavToPublicStorage(outFile, fileName)
                outFile.delete()
                mergedFile = null

                if (savedUri == null) {
                    call.reject("Không thể lưu file âm thanh vào bộ nhớ thiết bị.")
                    return@execute
                }

                val ret = JSObject()
                ret.put("uri", savedUri.toString())
                ret.put("fileName", fileName)
                call.resolve(ret)

            } catch (e: Exception) {
                call.reject("Lỗi khi tạo file âm thanh: ${e.message}", e)
            } finally {
                // Dọn dẹp file/thư mục tạm nếu còn sót lại do lỗi giữa chừng,
                // tránh rác tích lũy trong cache theo thời gian.
                try { tmpDir?.deleteRecursively() } catch (_: Exception) {}
                try { mergedFile?.delete() } catch (_: Exception) {}
            }
        }
    }

    // ------------------------------------------------------------------
    // Lấy danh sách toàn bộ giọng nói có sẵn trên máy (dùng để đổ vào dropdown)
    // ------------------------------------------------------------------
    @PluginMethod
    fun getVoices(call: PluginCall) {
        if (!ttsReady || tts == null) {
            call.reject("Bộ máy Text-to-Speech chưa sẵn sàng. Vui lòng thử lại sau vài giây.")
            return
        }
        val arr = JSArray()
        tts!!.voices?.forEach { v ->
            val obj = JSObject()
            obj.put("name", v.name)
            obj.put("lang", v.locale.toLanguageTag())
            arr.put(obj)
        }
        val ret = JSObject()
        ret.put("voices", arr)
        call.resolve(ret)
    }

    // ------------------------------------------------------------------
    // Đọc văn bản ra loa TRỰC TIẾP bằng TextToSpeech gốc của Android
    // (dùng thay cho Web Speech API vì WebView có thể không hỗ trợ đầy đủ)
    // ------------------------------------------------------------------
    private var speakCancelled = false

    @PluginMethod
    fun speak(call: PluginCall) {
        val text = call.getString("text")
        if (text.isNullOrBlank()) {
            call.reject("Thiếu văn bản cần đọc (text).")
            return
        }
        if (!ttsReady || tts == null) {
            call.reject("Bộ máy Text-to-Speech chưa sẵn sàng. Vui lòng thử lại sau vài giây.")
            return
        }

        val voiceName = call.getString("voiceName") ?: ""
        val lang = call.getString("lang") ?: "vi-VN"
        val rate = (call.getFloat("rate") ?: 1.0f)
        val pitch = (call.getFloat("pitch") ?: 1.0f)

        executor.execute {
            try {
                speakCancelled = false
                applyVoiceSettings(voiceName, lang, rate, pitch)
                val chunks = splitIntoChunks(text, SAFE_CHUNK_LEN)
                if (chunks.isEmpty()) {
                    call.reject("Văn bản rỗng sau khi xử lý.")
                    return@execute
                }

                for ((index, chunkText) in chunks.withIndex()) {
                    if (speakCancelled) break
                    val utteranceId = "speak_chunk_$index"
                    val latch = CountDownLatch(1)
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
                    tts!!.setOnUtteranceProgressListener(listener)

                    val params = Bundle()
                    val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    tts!!.speak(chunkText, queueMode, params, utteranceId)

                    latch.await(30, TimeUnit.SECONDS)
                    if (errored) break
                }
                call.resolve()
            } catch (e: Exception) {
                call.reject("Lỗi khi đọc văn bản: ${e.message}", e)
            }
        }
    }

    @PluginMethod
    fun stopSpeaking(call: PluginCall) {
        speakCancelled = true
        tts?.stop()
        call.resolve()
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
        } catch (e: Exception) {
            call.reject("Không thể mở hộp thoại chia sẻ: ${e.message}", e)
        }
    }

    // ------------------------------------------------------------------
    // Chọn giọng nói + áp dụng tốc độ/cao độ cho TTS engine
    // ------------------------------------------------------------------
    private fun applyVoiceSettings(voiceName: String, lang: String, rate: Float, pitch: Float) {
        val engine = tts ?: return
        var chosenVoice: Voice? = null

        if (voiceName.isNotBlank()) {
            chosenVoice = engine.voices?.firstOrNull { it.name == voiceName }
        }
        if (chosenVoice == null && lang.isNotBlank()) {
            val locale = parseLocale(lang)
            chosenVoice = engine.voices?.firstOrNull { it.locale.language == locale.language }
        }
        if (chosenVoice != null) {
            engine.voice = chosenVoice
        } else if (lang.isNotBlank()) {
            engine.language = parseLocale(lang)
        }

        engine.setSpeechRate(rate)
        engine.setPitch(pitch)
    }

    private fun parseLocale(bcp47: String): Locale {
        return try {
            Locale.forLanguageTag(bcp47)
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
                var chunkSize = readInt32LE(chunk
