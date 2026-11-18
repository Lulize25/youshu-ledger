package com.youshu.ledger.ui.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * 生物识别锁：启动及从后台返回时验证身份。
 * 优先指纹/人脸，未录入生物特征时回退设备凭据（锁屏密码/图案）。
 */
object AppLocker {

    private const val AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** 设备是否具备可用的验证方式（已录入指纹/人脸，或设置了锁屏密码） */
    fun canAuthenticate(activity: FragmentActivity): Boolean =
        BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /**
     * 弹出系统验证框。
     * @param onUnlocked 验证通过
     * @param onFailed   用户取消或验证不可用（调用方应退出应用）
     */
    fun requestUnlock(
        activity: FragmentActivity,
        onUnlocked: () -> Unit,
        onFailed: () -> Unit,
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onUnlocked()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onFailed()
            }

            // 单次识别失败（指纹不匹配等）：允许用户继续尝试，不回调
            override fun onAuthenticationFailed() = Unit
        }
        val prompt = BiometricPrompt(activity, executor, callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("解锁记一笔")
            .setSubtitle("验证身份后继续使用")
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        prompt.authenticate(info)
    }
}
