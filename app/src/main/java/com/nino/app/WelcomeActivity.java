/*
 * Nino - welcome / guidelines screen.
 *
 * First screen the user sees on launch. Shows what Nino is and how to use it,
 * reads the guidelines aloud with TextToSpeech, then starts the live camera
 * navigation automatically when the read-out finishes. A "Begin" button is
 * also provided so the user can start immediately without waiting for speech.
 */

package com.nino.app;

import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.util.Locale;
import com.nino.app.env.Logger;

public class WelcomeActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

  private static final Logger LOGGER = new Logger();
  private static final String UTTERANCE_ID_GUIDELINES = "nino-welcome-guidelines";
  private static final String UTTERANCE_ID_BEGIN = "nino-welcome-begin";

  private TextToSpeech tts;
  private boolean ttsReady = false;
  private boolean started = false;

  @Override
  protected void onCreate(final Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_welcome);

    final Button beginButton = (Button) findViewById(R.id.welcome_begin);
    beginButton.setOnClickListener(
        new View.OnClickListener() {
          @Override
          public void onClick(final View v) {
            // Tap skips the read-aloud and starts navigation right away.
            startNavigation(true);
          }
        });

    // TextToSpeech initializes asynchronously; onInit() is called when ready.
    tts = new TextToSpeech(this, this);
  }

  @Override
  public void onInit(final int status) {
    if (status != TextToSpeech.SUCCESS) {
      // TTS unavailable - the Begin button is the fallback path.
      LOGGER.e("TextToSpeech initialization failed, relying on Begin button.");
      return;
    }
    final int result = tts.setLanguage(Locale.ENGLISH);
    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
      return;
    }
    ttsReady = true;

    // Start the app automatically once the guidelines have been read out.
    tts.setOnUtteranceProgressListener(
        new UtteranceProgressListener() {
          @Override
          public void onStart(final String utteranceId) {}

          @Override
          public void onDone(final String utteranceId) {
            if (UTTERANCE_ID_GUIDELINES.equals(utteranceId)) {
              startNavigation(false);
            }
          }

          @Override
          public void onError(final String utteranceId) {
            startNavigation(false);
          }
        });

    speakGuidelines();
  }

  /** Reads the intro + guidelines aloud, then the app starts itself. */
  private void speakGuidelines() {
    if (!ttsReady) {
      return;
    }
    tts.speak(
        getString(R.string.welcome_speech_guidelines),
        TextToSpeech.QUEUE_FLUSH,
        null,
        UTTERANCE_ID_GUIDELINES);
  }

  /**
   * Launches the live camera navigation. Fires once from either the read-aloud
   * completion or the Begin button; harmless if invoked twice.
   */
  private void startNavigation(final boolean announceSkip) {
    if (started) {
      return;
    }
    started = true;

    if (announceSkip && ttsReady) {
      // A short confirmation so the user knows the hand-off happened even in
      // "begin immediately" mode.
      tts.speak(
          getString(R.string.welcome_begin_speech),
          TextToSpeech.QUEUE_FLUSH,
          null,
          UTTERANCE_ID_BEGIN);
    }

    final TextView status = (TextView) findViewById(R.id.welcome_status);
    if (status != null) {
      status.setText("");
    }

    runOnUiThread(
        new Runnable() {
          @Override
          public void run() {
            final Intent intent = new Intent(WelcomeActivity.this, DetectorActivity.class);
            startActivity(intent);
            finish();
          }
        });
  }

  @Override
  protected void onDestroy() {
    if (tts != null) {
      tts.stop();
      tts.shutdown();
      tts = null;
    }
    super.onDestroy();
  }
}