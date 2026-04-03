package me.grey.picquery.demo

import android.app.Application
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.grey.picquery.sdk.PicQueryEngine
import me.grey.picquery.sdk.model.PicQueryBackendPreference
import me.grey.picquery.sdk.model.PicQueryConfig
import me.grey.picquery.sdk.model.PicQueryModelPaths
import me.grey.picquery.sdk.model.PicQueryRuntimeInfo
import me.grey.picquery.sdk.search.PicQueryIndex
import me.grey.picquery.sdk.search.PicQueryIndexStore

class DemoViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val prefs = app.getSharedPreferences("picquery_demo", 0)
    private val modelsRootDir = File(app.filesDir, "models")
    private val managedModelsDir = File(modelsRootDir, "managed")
    private val externalModelsRootDir = File(app.getExternalFilesDir(null), "models")
    private val cacheDir = File(app.filesDir, "index")
    private val manifestFile = File(cacheDir, "gallery-index.tsv")
    private val indexFile = File(cacheDir, "gallery-index.bin")

    var uiState by mutableStateOf(DemoUiState())
        private set

    private var engine: PicQueryEngine? = null
    private var index: PicQueryIndex? = null
    private val imageManifest = linkedMapOf<String, GalleryImage>()
    private val busy = AtomicBoolean(false)

    init {
        restoreState()
    }

    fun updatePermission(granted: Boolean) {
        uiState = uiState.copy(permissionGranted = granted)
    }

    fun updateQuery(query: String) {
        uiState = uiState.copy(query = query)
    }

    fun importModel(role: ModelRole, uri: Uri) {
        viewModelScope.launch {
            setStatus("Importing ${role.name.lowercase()} model...")
            try {
                val target = withContext(Dispatchers.IO) {
                    ModelFileStore.copyToManagedLocation(app, role, uri, managedModelsDir)
                }
                when (role) {
                    ModelRole.IMAGE -> prefs.edit().putString(KEY_IMAGE_MODEL, target.absolutePath).apply()
                    ModelRole.TEXT -> prefs.edit().putString(KEY_TEXT_MODEL, target.absolutePath).apply()
                }
                rebuildEngine(clearIndex = false)
                setStatus("${role.name.lowercase().replaceFirstChar { it.uppercase() }} model ready")
            } catch (t: Throwable) {
                uiState = uiState.copy(error = t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun loadCachedIndexIfPossible() {
        if (uiState.indexedCount > 0 || !indexFile.exists() || !manifestFile.exists()) return
        viewModelScope.launch {
            try {
                val loadedIndex = withContext(Dispatchers.IO) { PicQueryIndexStore.load(indexFile) }
                val loadedManifest = withContext(Dispatchers.IO) { readManifest(manifestFile) }
                index = loadedIndex
                imageManifest.clear()
                imageManifest.putAll(loadedManifest)
                uiState = uiState.copy(indexedCount = loadedIndex.size, status = "Loaded cached gallery index")
            } catch (_: Throwable) {
                // Ignore stale cache and let the user rebuild.
            }
        }
    }

    fun rebuildIndex() {
        if (!busy.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                if (!uiState.permissionGranted) {
                    setStatus("Photo permission is required before indexing")
                    return@launch
                }
                val currentEngine = ensureEngine() ?: return@launch
                val images = withContext(Dispatchers.IO) { queryGalleryImages(app) }
                if (images.isEmpty()) {
                    setStatus("No local images found")
                    uiState = uiState.copy(indexedCount = 0, totalToIndex = 0)
                    return@launch
                }

                val freshIndex = PicQueryIndex()
                imageManifest.clear()
                uiState = uiState.copy(
                    indexing = true,
                    indexedCount = 0,
                    totalToIndex = images.size,
                    searchResults = emptyList(),
                    error = null,
                    status = "Indexing ${images.size} images..."
                )

                for ((position, image) in images.withIndex()) {
                    val bitmap = withContext(Dispatchers.IO) { loadBitmap(app, image.uri) }
                    if (bitmap != null) {
                        currentEngine.addToIndex(freshIndex, image.id, bitmap)
                        imageManifest[image.id] = image
                        bitmap.recycle()
                    }
                    uiState = uiState.copy(indexedCount = position + 1)
                }

                index = freshIndex
                withContext(Dispatchers.IO) {
                    PicQueryIndexStore.save(indexFile, freshIndex)
                    writeManifest(manifestFile, imageManifest.values.toList())
                }
                val runtimeInfo = currentEngine.runtimeInfo()
                uiState = uiState.copy(
                    indexing = false,
                    indexedCount = freshIndex.size,
                    totalToIndex = freshIndex.size,
                    runtimeInfo = runtimeInfo,
                    status = "Indexed ${freshIndex.size} images with ${runtimeInfo.imageBackend}"
                )
            } catch (t: Throwable) {
                uiState = uiState.copy(indexing = false, error = t.message ?: t.javaClass.simpleName)
            } finally {
                busy.set(false)
            }
        }
    }

    fun runSearch() {
        val query = uiState.query.trim()
        val currentIndex = index
        val currentEngine = engine
        if (query.isEmpty()) {
            uiState = uiState.copy(searchResults = emptyList())
            return
        }
        if (currentIndex == null || currentEngine == null) {
            setStatus("Build the gallery index first")
            return
        }
        viewModelScope.launch {
            try {
                val hits = withContext(Dispatchers.Default) {
                    currentEngine.search(query, currentIndex, topK = 24)
                }
                val results = hits.mapNotNull { hit ->
                    imageManifest[hit.id]?.let { image ->
                        DemoSearchResult(
                            id = hit.id,
                            uri = image.uri,
                            displayName = image.displayName,
                            score = hit.score
                        )
                    }
                }
                uiState = uiState.copy(
                    searchResults = results,
                    status = "Found ${results.size} matches"
                )
            } catch (t: Throwable) {
                uiState = uiState.copy(error = t.message ?: t.javaClass.simpleName)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        engine?.close()
    }

    private suspend fun rebuildEngine(clearIndex: Boolean) {
        engine?.close()
        engine = null
        val previousImagePath = uiState.imageModelPath
        val previousTextPath = uiState.textModelPath
        val imagePath = resolveExistingModelPath(
            role = ModelRole.IMAGE,
            preferredPath = prefs.getString(KEY_IMAGE_MODEL, null)
        )
        val textPath = resolveExistingModelPath(
            role = ModelRole.TEXT,
            preferredPath = prefs.getString(KEY_TEXT_MODEL, null)
        )
        val hasModels = !imagePath.isNullOrBlank() && !textPath.isNullOrBlank()
        if (hasModels) {
            prefs.edit()
                .putString(KEY_IMAGE_MODEL, imagePath)
                .putString(KEY_TEXT_MODEL, textPath)
                .apply()
        }
        uiState = uiState.copy(
            imageModelPath = imagePath,
            textModelPath = textPath,
            modelsReady = hasModels,
            runtimeInfo = null,
            error = null
        )
        if (!hasModels) return
        engine = withContext(Dispatchers.IO) {
                PicQueryEngine(
                    app,
                    PicQueryConfig(
                        modelPaths = PicQueryModelPaths(imagePath, textPath),
                        backendPreference = PicQueryBackendPreference.GPU,
                        cpuThreads = 4
                    )
                )
        }
        val modelChanged = clearIndex || previousImagePath != imagePath || previousTextPath != textPath
        if (modelChanged) {
            index = null
            imageManifest.clear()
            deleteCachedIndexFiles()
            uiState = uiState.copy(indexedCount = 0, totalToIndex = 0, searchResults = emptyList())
        }
        uiState = uiState.copy(runtimeInfo = engine?.runtimeInfo())
    }

    private suspend fun ensureEngine(): PicQueryEngine? {
        if (engine == null) {
            rebuildEngine(clearIndex = false)
        }
        val currentEngine = engine
        if (currentEngine == null) {
            setStatus("Import both model files first, or push them to the app models folder")
        }
        return currentEngine
    }

    private fun resolveExistingModelPath(role: ModelRole, preferredPath: String?): String? {
        val candidates = buildList {
            preferredPath?.let(::add)
            addAll(discoverModelCandidates(modelsRootDir, role))
            addAll(discoverModelCandidates(externalModelsRootDir, role))
        }
        return candidates.firstOrNull { path ->
            path != null && File(path).exists()
        }
    }

    private fun discoverModelCandidates(rootDir: File?, role: ModelRole): List<String> {
        if (rootDir == null || !rootDir.exists()) return emptyList()
        val expectedNames = when (role) {
            ModelRole.IMAGE -> setOf("mobileclip2_image.tflite", "mobileclip2_image.onnx", "mobileclip2_image.ort")
            ModelRole.TEXT -> setOf("mobileclip2_text.ort", "mobileclip2_text.onnx")
        }
        return rootDir.walkTopDown()
            .maxDepth(3)
            .filter { it.isFile && it.name in expectedNames }
            .map { it.absolutePath }
            .toList()
    }

    private fun deleteCachedIndexFiles() {
        indexFile.delete()
        manifestFile.delete()
    }

    private fun restoreState() {
        val granted = PermissionChecker.hasImagePermission(app)
        uiState = uiState.copy(permissionGranted = granted)
        viewModelScope.launch {
            rebuildEngine(clearIndex = false)
            loadCachedIndexIfPossible()
        }
    }

    private fun setStatus(message: String) {
        uiState = uiState.copy(status = message, error = null)
    }

    private fun queryGalleryImages(application: Application): List<GalleryImage> {
        val resolver = application.contentResolver
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        val items = mutableListOf<GalleryImage>()
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn) ?: "image_$id"
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                items += GalleryImage(id = id.toString(), uri = uri, displayName = name)
            }
        }
        return items
    }

    private fun loadBitmap(application: Application, uri: Uri, maxSize: Int = 512): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        application.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxSize)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize
        }
        return application.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxSize: Int): Int {
        var sampleSize = 1
        var currentWidth = width
        var currentHeight = height
        while (currentWidth > maxSize || currentHeight > maxSize) {
            currentWidth /= 2
            currentHeight /= 2
            sampleSize *= 2
        }
        return sampleSize.coerceAtLeast(1)
    }

    private fun writeManifest(file: File, images: List<GalleryImage>) {
        file.parentFile?.mkdirs()
        file.writeText(images.joinToString("\n") { image ->
            listOf(image.id, image.displayName.replace('\t', ' '), image.uri.toString()).joinToString("\t")
        })
    }

    private fun readManifest(file: File): Map<String, GalleryImage> {
        if (!file.exists()) return emptyMap()
        return file.readLines()
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 3) return@mapNotNull null
                val id = parts[0]
                val name = parts[1]
                val uri = Uri.parse(parts.subList(2, parts.size).joinToString("\t"))
                id to GalleryImage(id, uri, name)
            }
            .toMap(linkedMapOf())
    }

    companion object {
        private const val KEY_IMAGE_MODEL = "image_model_path"
        private const val KEY_TEXT_MODEL = "text_model_path"
    }
}

data class DemoUiState(
    val permissionGranted: Boolean = false,
    val imageModelPath: String? = null,
    val textModelPath: String? = null,
    val modelsReady: Boolean = false,
    val indexing: Boolean = false,
    val indexedCount: Int = 0,
    val totalToIndex: Int = 0,
    val query: String = "",
    val searchResults: List<DemoSearchResult> = emptyList(),
    val runtimeInfo: PicQueryRuntimeInfo? = null,
    val status: String = "Import both model files, grant photo permission, then build the index.",
    val error: String? = null
)

data class DemoSearchResult(
    val id: String,
    val uri: Uri,
    val displayName: String,
    val score: Float
)

data class GalleryImage(
    val id: String,
    val uri: Uri,
    val displayName: String
)

private object PermissionChecker {
    fun hasImagePermission(application: Application): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return androidx.core.content.ContextCompat.checkSelfPermission(
            application,
            permission
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
