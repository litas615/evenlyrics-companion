package com.litas615.evenlyrics.companion

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvPermissionPill: TextView
    private lateinit var tvPermissionStatus: TextView
    private lateinit var btnPermission: Button
    private lateinit var tvServerStatus: TextView
    private lateinit var tvTrackTitle: TextView
    private lateinit var tvTrackArtist: TextView
    private lateinit var tvTrackStatus: TextView
    private lateinit var btnTestLyrics: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvPermissionPill = findViewById(R.id.tvPermissionPill)
        tvPermissionStatus = findViewById(R.id.tvPermissionStatus)
        btnPermission = findViewById(R.id.btnPermission)
        tvServerStatus = findViewById(R.id.tvServerStatus)
        tvTrackTitle = findViewById(R.id.tvTrackTitle)
        tvTrackArtist = findViewById(R.id.tvTrackArtist)
        tvTrackStatus = findViewById(R.id.tvTrackStatus)
        btnTestLyrics = findViewById(R.id.btnTestLyrics)

        btnPermission.setOnClickListener {
            openNotificationListenerSettings()
        }

        btnTestLyrics.setOnClickListener {
            val service = EvenLyricsMediaService.instance
            if (service != null) {
                service.injectMockTrack(
                    mockTitle = "EvenLyrics 示範曲",
                    mockArtist = "EvenLyrics",
                    mockDurationMs = 60000L,
                    mockLrc = """
                        [00:00.00]EvenLyrics 示範曲
                        [00:01.00]耳機裡的旋律剛剛開始
                        [00:05.00]歌詞跟著浮現
                        [00:09.00]抬頭就能看見
                        [00:13.00]走在街上也不必低頭找手機
                        [00:17.00]每一句都準時來到眼前
                    """.trimIndent()
                )
                Toast.makeText(this, "✓ 已推送示範歌詞！請查看 Even G2 眼鏡", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "請先啟用通知權限啟動服務", Toast.LENGTH_SHORT).show()
            }
        }

        EvenLyricsMediaService.onStateChangedListener = { music ->
            runOnUiThread {
                tvTrackTitle.text = music.title.ifEmpty { "無歌曲播放中" }
                tvTrackArtist.text = if (music.artist.isNotEmpty()) music.artist else music.packageName
                tvTrackStatus.text = if (music.isPlaying) "▶ 播放中" else "⏸ 已暫停"
                tvTrackStatus.setTextColor(
                    if (music.isPlaying) 0xFF00FF66.toInt() else 0xFFFFA726.toInt()
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionUI()
        updateServerUI()
    }

    private fun updatePermissionUI() {
        val isGranted = isNotificationServiceEnabled()
        if (isGranted) {
            tvPermissionPill.text = "● 已授權"
            tvPermissionPill.setTextColor(0xFF00FF66.toInt())
            tvPermissionPill.setBackgroundResource(R.drawable.bg_pill_status)
            tvPermissionStatus.text = "✓ 媒體播放監聽服務正常運行中"
            tvPermissionStatus.setTextColor(0xFF8A9E90.toInt())

            btnPermission.visibility = Button.VISIBLE
            btnPermission.text = "⚙️ 系統權限設定 (運作中)"
            btnPermission.setBackgroundResource(R.drawable.btn_secondary_outline)
            btnPermission.setTextColor(0xFF00FF66.toInt())
        } else {
            tvPermissionPill.text = "! 待啟用"
            tvPermissionPill.setTextColor(0xFFFF5252.toInt())
            tvPermissionPill.setBackgroundResource(R.drawable.bg_pill_error)
            tvPermissionStatus.text = "⚠️ 請啟用「通知存取權限」以讀取音樂時間軸"
            tvPermissionStatus.setTextColor(0xFFFF5252.toInt())

            btnPermission.visibility = Button.VISIBLE
            btnPermission.text = "🔑 前往啟用通知存取權限"
            btnPermission.setBackgroundResource(R.drawable.btn_primary_cyber)
            btnPermission.setTextColor(0xFF06180E.toInt())
        }
    }

    private fun updateServerUI() {
        val service = EvenLyricsMediaService.instance
        if (service != null) {
            tvServerStatus.text = "● 服務運作中 (127.0.0.1:5288)\nEven G2 眼鏡隨時可直連"
            tvServerStatus.setTextColor(0xFF00FF66.toInt())
        } else {
            tvServerStatus.text = "○ 等待系統喚醒服務 (請先授予上方權限)"
            tvServerStatus.setTextColor(0xFF8A9E90.toInt())
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val pkgName = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (!flat.isNullOrEmpty()) {
            val names = flat.split(":")
            for (name in names) {
                val cn = ComponentName.unflattenFromString(name)
                if (cn != null && cn.packageName == pkgName) {
                    return true
                }
            }
        }
        return false
    }

    private fun openNotificationListenerSettings() {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        } catch (_: Exception) {
            val intent = Intent(Settings.ACTION_SETTINGS)
            startActivity(intent)
        }
    }
}
