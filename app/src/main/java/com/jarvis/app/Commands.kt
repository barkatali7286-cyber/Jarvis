package com.jarvis.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Commands {
    private val fillers = setOf("call", "phone", "number", "karo", "do", "open", "kholo", "chalao", "bajaao", "ka", "ki", "ko", "par", "mein", "wala", "wali")

    fun run(context: Context, text: String): String? {
        val words = text.split(" ")
        val t = words.filter { !fillers.contains(it) }.joinToString(" ")

        when {
            t.contains("youtube") -> {
                val intent = context.packageManager.getLaunchIntentForPackage("com.google.android.youtube")
                intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent != null) {
                    context.startActivity(intent)
                }
                return "YouTube khol diya hai boss."
            }
            t.contains("whatsapp") -> {
                val intent = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
                intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent != null) {
                    context.startActivity(intent)
                }
                return "WhatsApp khol diya hai boss."
            }
            t.contains("torch on") || t.contains("light jalao") -> {
                return try {
                    val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val id = cm.cameraIdList.getOrNull(0)
                    if (id != null) {
                        cm.setTorchMode(id, true)
                    }
                    "Torch on kar di hai boss."
                } catch (e: Exception) {
                    e.printStackTrace()
                    "Torch on nahi ho payi boss."
                }
            }
            t.contains("torch off") || t.contains("light bujhaao") -> {
                return try {
                    val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val id = cm.cameraIdList.getOrNull(0)
                    if (id != null) {
                        cm.setTorchMode(id, false)
                    }
                    "Torch off kar di hai boss."
                } catch (e: Exception) {
                    e.printStackTrace()
                    "Torch off nahi ho payi boss."
                }
            }
            t.contains("time") || t.contains("samay") -> {
                val time = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
                return "Abhi samay $time ho raha hai boss."
            }
            t.contains("date") || t.contains("tareekh") -> {
                val date = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault()).format(Date())
                return "Aaj ki tareekh $date hai boss."
            }
        }
        return null
    }
}
