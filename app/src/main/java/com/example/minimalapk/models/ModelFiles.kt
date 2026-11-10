package com.example.minimalapk.models

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

internal fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this).joinToString("") { "%02x".format(it) }

internal fun File.writeAtomically(bytes: ByteArray) {
    val directory = requireNotNull(absoluteFile.parentFile)
    check(directory.isDirectory || directory.mkdirs()) { "无法创建模型目录" }
    val temp = File.createTempFile(name, ".tmp", directory)
    try {
        FileOutputStream(temp).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        check(temp.renameTo(this)) { "无法保存 $name，原文件已保留" }
    } finally {
        temp.delete()
    }
}
