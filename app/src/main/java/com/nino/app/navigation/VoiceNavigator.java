/*
 * Nino - spoken navigation feedback.
 *
 * Owns the Android TextToSpeech engine and applies throttling so the app talks
 * at a sensible pace instead of on every single camera frame. Uses
 * NavigationGuidance to decide what to say.
 */

package com.nino.app.navigation;

import android.content.Context;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import java.util.List;
import java.util.Locale;
import com.nino.app.tflite.Classifier.Recognition;

public class VoiceNavigator implements TextToSpeech.OnInitListener {

  /** Callback so the UI can mirror the latest spoken guidance on screen. */
  public interface OnGuidanceListener {
    void onGuidance(String spokenPhrase, NavigationGuidance.Guidance guidance);
  }

  /** Minimum gap (ms) between two normal spoken messages. */
  private static final long COOLDOWN_MS = 2500L;

  /** Urgent (very close) messages may interrupt sooner than the normal cooldown. */
  private static final long URGENT_COOLDOWN_MS = 900L;

  /** The exact same phrase is not re-announced within this window (avoid nagging). */
  private static final long REPEAT_SUPPRESS_MS = 8000L;

  private final OnGuidanceListener listener;
  private TextToSpeech tts;
  private boolean ttsReady = false;
  private boolean muted = false;

  private long lastSpokenTimeMs = 0L;
  private String lastPhrase = "";

  public VoiceNavigator(Context context, OnGuidanceListener listener) {
    this.listener = listener;
    // TextToSpeech initializes asynchronously; onInit() is called when ready.
    tts = new TextToSpeech(context, this);
  }

  @Override
  public void onInit(int status) {
    if (status != TextToSpeech.SUCCESS) {
      return;
    }
    int result = tts.setLanguage(Locale.ENGLISH);
    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
      return;
    }
    // Slightly slower than default so instructions are easy to follow.
    tts.setSpeechRate(0.95f);
    ttsReady = true;
  }

  /**
   * Called from the detection loop with every frame's filtered detections.
   * Picks the most relevant object, builds the phrase and, if it passes the
   * cooldown rules, speaks it.
   */
  public synchronized void onNewDetections(
      List<Recognition> detections, float frameWidth, float frameHeight) {
    NavigationGuidance.Guidance guidance =
        NavigationGuidance.selectMostRelevant(detections, frameWidth, frameHeight);
    if (guidance == null) {
      return;
    }

    String phrase =
        NavigationGuidance.buildPhrase(guidance.getTitle(), guidance.getZone(), guidance.getUrgency());

    // When muted we stay silent but still report the state to the UI.
    if (!muted && shouldSpeak(phrase, guidance)) {
      speak(phrase);
      lastPhrase = phrase;
      lastSpokenTimeMs = SystemClock.elapsedRealtime();
    }

    if (listener != null) {
      listener.onGuidance(phrase, guidance);
    }
  }

  /** Turns spoken feedback on/off. The on-screen state still updates when muted. */
  public synchronized void setMuted(boolean muted) {
    this.muted = muted;
    if (muted && tts != null) {
      tts.stop();
    }
  }

  public synchronized boolean isMuted() {
    return muted;
  }

  /**
   * Decides whether a new phrase may be spoken:
   *  1. always respect a minimum cooldown (shorter for urgent messages);
   *  2. do not repeat the exact same phrase for a while unless it turned urgent.
   */
  private boolean shouldSpeak(String phrase, NavigationGuidance.Guidance guidance) {
    long now = SystemClock.elapsedRealtime();
    long elapsed = now - lastSpokenTimeMs;

    boolean isUrgent = guidance.getUrgency() == NavigationGuidance.Urgency.VERY_CLOSE;
    long minimumCooldown = isUrgent ? URGENT_COOLDOWN_MS : COOLDOWN_MS;
    if (elapsed < minimumCooldown) {
      return false;
    }

    boolean isRepeat = phrase.equals(lastPhrase);
    if (isRepeat && !isUrgent && elapsed < REPEAT_SUPPRESS_MS) {
      return false;
    }

    return true;
  }

  private void speak(String message) {
    if (!ttsReady) {
      return;
    }
    // QUEUE_FLUSH interrupts any still-playing announcement so guidance is always current.
    tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "nino-guidance");
  }

  /** Releases the TTS engine; must be called from the Activity's onDestroy. */
  public void shutdown() {
    if (tts != null) {
      tts.stop();
      tts.shutdown();
      tts = null;
      ttsReady = false;
    }
  }
}
