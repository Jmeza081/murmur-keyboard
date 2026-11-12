package dev.murmur.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import dev.murmur.assistant.MurmurAccessibilityService
import dev.murmur.speech.ModelManager

/** What's set up on this phone, read fresh each time (the user changes these in Android's settings). */
object SetupState {
    fun keyboardEnabled(ctx: Context) =
        ctx.getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == ctx.packageName }

    fun keyboardIsDefault(ctx: Context) =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/') == ctx.packageName

    fun modelInstalled(ctx: Context) = ModelManager.isInstalled(ctx)

    /** Murmur is the digital assistant, so holding the power button opens its listening sheet. */
    fun isAssistant(ctx: Context) =
        Settings.Secure.getString(ctx.contentResolver, "voice_interaction_service")?.startsWith(ctx.packageName + "/") == true

    fun accessibilityOn(ctx: Context): Boolean {
        val me = ComponentName(ctx, MurmurAccessibilityService::class.java).flattenToString()
        val on = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return on.split(':').any { it.equals(me, ignoreCase = true) }
    }

    /** The three steps dictation and typing need; power button and accessibility are optional extras. */
    fun essentialsDone(ctx: Context) = keyboardEnabled(ctx) && keyboardIsDefault(ctx) && modelInstalled(ctx)

    fun openKeyboardList(ctx: Context) = ctx.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    fun openKeyboardPicker(ctx: Context) = ctx.getSystemService(InputMethodManager::class.java).showInputMethodPicker()
    fun openAssistantSettings(ctx: Context) = ctx.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    fun openAccessibility(ctx: Context) = ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private const val SEEN = "setup_seen"
    /** The checklist shows on launch until the essentials are done, or until you skip it once. */
    fun skipped(ctx: Context) = ctx.getSharedPreferences("murmur", Context.MODE_PRIVATE).getBoolean(SEEN, false)
    fun setSkipped(ctx: Context) = ctx.getSharedPreferences("murmur", Context.MODE_PRIVATE).edit().putBoolean(SEEN, true).apply()
}
