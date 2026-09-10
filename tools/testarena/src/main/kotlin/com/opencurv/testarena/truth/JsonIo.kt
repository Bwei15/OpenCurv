package com.opencurv.testarena.truth

import com.google.gson.GsonBuilder
import java.io.File

object JsonIo {
    val gson = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .serializeNulls()
        .create()

    fun writeArenaTruth(truth: ArenaTruth, file: File) {
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(truth) + "\n", Charsets.UTF_8)
    }

    fun readArenaTruth(file: File): ArenaTruth =
        file.reader(Charsets.UTF_8).use { gson.fromJson(it, ArenaTruth::class.java) }
}
