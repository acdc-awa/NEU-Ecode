package com.neboer.ecode

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Gainmap
import android.os.Build
import android.view.WindowManager
import androidx.annotation.RequiresApi
import com.google.zxing.common.BitMatrix

object HdrHelper {

    /**
     * 检查系统版本与屏幕硬件是否支持 Ultra HDR / Gainmap 渲染
     * 需 Android 14 (API 34) 及以上，且屏幕具备 HDR 显示能力
     */
    fun isHdrSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return false
        }
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
        } ?: return false

        // API 34+ 优先使用 display.mode.supportedHdrTypes
        val modeHdrTypes = display.mode.supportedHdrTypes
        if (modeHdrTypes.isNotEmpty()) return true

        @Suppress("DEPRECATION")
        val hdrCapabilities = display.hdrCapabilities ?: return false
        @Suppress("DEPRECATION")
        val supportedTypes = hdrCapabilities.supportedHdrTypes ?: return false
        return supportedTypes.isNotEmpty()
    }

    /**
     * 为二维码位图附加 HDR 增益遮罩 (Gainmap)
     * - 二维码白色部分与静区 (false) 赋予满额增益 (255)，在 HDR 屏幕上激发峰值高亮
     * - 二维码黑色模块 (true) 赋予零增益 (0)，保持纯黑，实现极致黑白反差
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun attachGainmap(bitmap: Bitmap, bitMatrix: BitMatrix, size: Int, maxRatio: Float = 8.0f) {
        val gainmapBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val whiteGain = Color.rgb(255, 255, 255)
        val blackGain = Color.rgb(0, 0, 0)

        for (x in 0 until size) {
            for (y in 0 until size) {
                // bitMatrix[x, y] 为 true 表示黑色模块，无增益；false 表示白底，满额增益
                gainmapBitmap.setPixel(x, y, if (bitMatrix[x, y]) blackGain else whiteGain)
            }
        }

        val gainmap = Gainmap(gainmapBitmap).apply {
            setRatioMin(1.0f, 1.0f, 1.0f)
            setRatioMax(maxRatio, maxRatio, maxRatio)
            setDisplayRatioForFullHdr(maxRatio)
            setMinDisplayRatioForHdrTransition(1.0f)
            setGamma(1.0f, 1.0f, 1.0f)
            setEpsilonSdr(0f, 0f, 0f)
            setEpsilonHdr(0f, 0f, 0f)
        }

        bitmap.gainmap = gainmap
    }
}
