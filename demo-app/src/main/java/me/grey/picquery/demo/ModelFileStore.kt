package me.grey.picquery.demo

import android.content.Context
import android.net.Uri
import java.io.File

object ModelFileStore {
    fun copyToManagedLocation(
        context: Context,
        role: ModelRole,
        source: Uri,
        modelsDir: File
    ): File {
        modelsDir.mkdirs()
        val derivedExtension = source.lastPathSegment
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() }
            ?.let { ".$it.lowercase()" }
        val fallbackExtension = when (role) {
            ModelRole.IMAGE -> ".tflite"
            ModelRole.TEXT -> ".ort"
        }
        val finalExtension = when {
            derivedExtension in listOf(".tflite", ".onnx", ".ort") -> derivedExtension
            else -> fallbackExtension
        }
        val targetName = when (role) {
            ModelRole.IMAGE -> "mobileclip2_image$finalExtension"
            ModelRole.TEXT -> "mobileclip2_text$finalExtension"
        }
        val target = File(modelsDir, targetName)
        context.contentResolver.openInputStream(source)?.use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: error("Unable to read $source")
        return target
    }
}
