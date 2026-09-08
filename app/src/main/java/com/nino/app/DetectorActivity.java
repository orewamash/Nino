/* Nino - Navigation Assistant for the Visually Impaired. */

package com.nino.app;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Size;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import com.nino.app.customview.OverlayView;
import com.nino.app.customview.OverlayView.DrawCallback;
import com.nino.app.customview.SoundingChartView;
import com.nino.app.customview.SoundingChartView.Blip;
import com.nino.app.env.BorderedText;
import com.nino.app.env.ImageUtils;
import com.nino.app.env.Logger;
import com.nino.app.navigation.NavigationGuidance;
import com.nino.app.navigation.NavigationGuidance.Guidance;
import com.nino.app.navigation.NavigationGuidance.Urgency;
import com.nino.app.navigation.VoiceNavigator;
import com.nino.app.tflite.Classifier;
import com.nino.app.tflite.Classifier.Recognition;
import com.nino.app.tflite.TFLiteObjectDetectionAPIModel;
import com.nino.app.tracking.MultiBoxTracker;

/**
 * DetectorActivity hosts the live camera preview, runs on-device object detection on every frame
 * and announces spoken navigation guidance based on what it sees.
 */
public class DetectorActivity extends CameraActivity implements OnImageAvailableListener {
  private static final Logger LOGGER = new Logger();

  // Configuration values for the prepackaged EfficientDet-Lite0 model.
  private static final int TF_OD_API_INPUT_SIZE = 320;
  private static final boolean TF_OD_API_IS_QUANTIZED = true;
  private static final String TF_OD_API_MODEL_FILE = "detect.tflite";
  private static final String TF_OD_API_LABELS_FILE = "file:///android_asset/labelmap.txt";
  private static final DetectorMode MODE = DetectorMode.TF_OD_API;
  // Minimum detection confidence to track a detection.
  private static final float MINIMUM_CONFIDENCE_TF_OD_API = 0.57f;//minimum confidence bumped from 0.5 to 0.6
  private static final boolean MAINTAIN_ASPECT = false;
  private static final Size DESIRED_PREVIEW_SIZE = new Size(2246, 1080); //changed from 640X480 to testing it on poco f1//it actually goes to 1600X1200
  private static final boolean SAVE_PREVIEW_BITMAP = false;
  private static final float TEXT_SIZE_DIP = 10;
  OverlayView trackingOverlay;
  private Integer sensorOrientation;

  private Classifier detector;

  private long lastProcessingTimeMs;
  private Bitmap rgbFrameBitmap = null;
  private Bitmap croppedBitmap = null;
  private Bitmap cropCopyBitmap = null;

  private boolean computingDetection = false;

  private long timestamp = 0;

  private Matrix frameToCropTransform;
  private Matrix cropToFrameTransform;

  private MultiBoxTracker tracker;

  private BorderedText borderedText;

  private VoiceNavigator voiceNavigator;

  private TextView guidanceStatusView;

  private View guidanceBeamView;
  private TextView guidanceMetaView;
  private TextView guidanceTickerView;
  private View courseTickLeft, courseTickCenter, courseTickRight;
  private SoundingChartView soundingChart;
  private Button dockVoiceButton;
  private int beamColor = Color.rgb(64, 224, 194);
  private float beamTargetScale = 0.72f;
  private String lastCardKey = "";

  // Latest confidences + titles so the scene re-read can summarize what Nino sees.
  private final LinkedList<Guidance> lastFrameGuidance = new LinkedList<Guidance>();

  @Override
  protected void onCreate(final Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    // Starts the on-device TextToSpeech engine; speaks navigation guidance.
    voiceNavigator =
        new VoiceNavigator(
            this,
            new VoiceNavigator.OnGuidanceListener() {
              @Override
              public void onGuidance(
                  final String spokenPhrase, final NavigationGuidance.Guidance guidance) {
                // Mirror the spoken instruction on screen: phrase + beam + meta + course rule.
                runOnUiThread(
                    () -> updateGuidanceCard(spokenPhrase, guidance));
              }
            });
  }

  @Override
  public synchronized void onDestroy() {
    super.onDestroy();
    if (voiceNavigator != null) {
      voiceNavigator.shutdown();
    }
  }

  @Override
  public void onPreviewSizeChosen(final Size size, final int rotation) {
    final float textSizePx =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, getResources().getDisplayMetrics());
    borderedText = new BorderedText(textSizePx);
    borderedText.setTypeface(Typeface.MONOSPACE);

    tracker = new MultiBoxTracker(this);

    int cropSize = TF_OD_API_INPUT_SIZE;

    try {
      detector =
          TFLiteObjectDetectionAPIModel.create(
              getAssets(),
              TF_OD_API_MODEL_FILE,
              TF_OD_API_LABELS_FILE,
              TF_OD_API_INPUT_SIZE,
              TF_OD_API_IS_QUANTIZED);
      cropSize = TF_OD_API_INPUT_SIZE;
    } catch (final IOException e) {
      e.printStackTrace();
      LOGGER.e(e, "Exception initializing classifier!");
      Toast toast =
          Toast.makeText(
              getApplicationContext(), "Classifier could not be initialized", Toast.LENGTH_SHORT);
      toast.show();
      finish();
    }

    previewWidth = size.getWidth();
    previewHeight = size.getHeight();

    sensorOrientation = rotation - getScreenOrientation();
    LOGGER.i("Camera orientation relative to screen canvas: %d", sensorOrientation);

    LOGGER.i("Initializing at size %dx%d", previewWidth, previewHeight);
    rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888);
    croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Config.ARGB_8888);

    frameToCropTransform =
        ImageUtils.getTransformationMatrix(
            previewWidth, previewHeight,
            cropSize, cropSize,
            sensorOrientation, MAINTAIN_ASPECT);

    cropToFrameTransform = new Matrix();
    frameToCropTransform.invert(cropToFrameTransform);

    trackingOverlay = (OverlayView) findViewById(R.id.tracking_overlay);
    guidanceStatusView = findViewById(R.id.guidance_phrase);
    guidanceBeamView = findViewById(R.id.guidance_beam);
    guidanceMetaView = findViewById(R.id.guidance_meta);
    guidanceTickerView = findViewById(R.id.guidance_ticker);
    courseTickLeft = findViewById(R.id.course_tick_left);
    courseTickCenter = findViewById(R.id.course_tick_center);
    courseTickRight = findViewById(R.id.course_tick_right);
    soundingChart = findViewById(R.id.sounding_chart);
    dockVoiceButton = findViewById(R.id.dock_voice);

    findViewById(R.id.dock_scan).setOnClickListener(v -> readScene());
    findViewById(R.id.dock_settings)
        .setOnClickListener(
            v -> {
              BottomSheetBehavior behavior = BottomSheetBehavior.from(findViewById(R.id.bottom_sheet_layout));
              behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            });
    dockVoiceButton.setOnClickListener(v -> toggleVoice());
    updateVoiceButton();

    // The sweep "still scanning" cue: a quiet, looping rotation of the radar sweep.
    final ValueAnimator sweep = ValueAnimator.ofFloat(0f, 360f);
    sweep.setDuration(5200L);
    sweep.setRepeatCount(ValueAnimator.INFINITE);
    sweep.setInterpolator(new DecelerateInterpolator());
    sweep.addUpdateListener(
        animator -> {
          if (soundingChart != null) {
            soundingChart.setSweepAngle((Float) animator.getAnimatedValue());
          }
        });
    sweep.start();

    trackingOverlay.addCallback(
        new DrawCallback() {
          @Override
          public void drawCallback(final Canvas canvas) {
            tracker.draw(canvas);
            if (isDebug()) {
              tracker.drawDebug(canvas);
            }
          }
        });

    tracker.setFrameConfiguration(previewWidth, previewHeight, sensorOrientation);
  }

  /** Mirrors one guidance decision onto the beam card: phrase, meta, ticks, beam, radar. */
  private void updateGuidanceCard(final String spokenPhrase, final Guidance guidance) {
    if (guidanceStatusView == null || guidance == null) {
      return;
    }
    final String text =
        voiceNavigator.isMuted() ? getString(R.string.guidance_muted) : spokenPhrase;

    // The detector fires every frame; only re-render the beam pulse/ticker when the
    // guidance actually changed (mirrors the mock's data-lived re-fire).
    final String key = text + "|" + stateWord(guidance.getUrgency()) + "|" + zoneWord(guidance.getZone());
    final boolean changed = !key.equals(lastCardKey);
    lastCardKey = key;

    guidanceStatusView.setText(text);

    final String zone = zoneWord(guidance.getZone());
    final String state = stateWord(guidance.getUrgency());
    final int conf = Math.round(guidance.getConfidence() * 100);
    guidanceMetaView.setText(
        zone + " \u00b7 " + state + " \u00b7 " + conf + "%");

    setBeamUrgency(guidance.getUrgency());
    setCourseTicks(guidance.getZone(), guidance.getUrgency());

    // Focus lock: light the buoy currently being spoken about.
    if (tracker != null) {
      tracker.setFocusTitle(guidance.getTitle());
    }

    if (changed) {
      pulseBeam();
      guidanceTickerView.setText(spokenPhrase);
    }
  }

  private void setBeamUrgency(final Urgency urgency) {
    switch (urgency) {
      case VERY_CLOSE:
        beamColor = Color.rgb(255, 95, 82);
        beamTargetScale = 0.46f;
        break;
      case CLOSE:
        beamColor = Color.rgb(255, 182, 75);
        beamTargetScale = 0.58f;
        break;
      default:
        beamColor = Color.rgb(64, 224, 194);
        beamTargetScale = 0.72f;
        break;
    }
    if (guidanceBeamView != null) {
      guidanceBeamView
          .animate()
          .scaleX(beamTargetScale)
          .setDuration(400L)
          .start();
      setViewColor(guidanceBeamView, beamColor);
    }
  }

  /** One fast scale dip, the "beam lick" fired when Nino speaks. */
  private void pulseBeam() {
    if (guidanceBeamView == null) {
      return;
    }
    guidanceBeamView
        .animate()
        .scaleY(2.4f)
        .alpha(0.35f)
        .setDuration(120L)
        .withEndAction(
            () ->
                guidanceBeamView
                    .animate()
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(360L)
                    .start())
        .start();
  }

  private void setCourseTicks(final NavigationGuidance.Zone zone, final Urgency urgency) {
    final int idle = R.drawable.cr_tick;
    final int clear = R.drawable.cr_tick_clear;
    final int active = R.drawable.cr_tick_active;
    final View[] ticks = {courseTickLeft, courseTickCenter, courseTickRight};
    final int urgencyColor =
        urgency == Urgency.VERY_CLOSE
            ? Color.rgb(255, 95, 82)
            : urgency == Urgency.CLOSE ? Color.rgb(255, 182, 75) : Color.rgb(64, 224, 194);
    final int clearColor = Color.rgb(64, 224, 194);
    final NavigationGuidance.Zone[] zones = {
      NavigationGuidance.Zone.LEFT, NavigationGuidance.Zone.CENTER, NavigationGuidance.Zone.RIGHT
    };
    for (int i = 0; i < ticks.length; i++) {
      if (ticks[i] == null) {
        continue;
      }
      final boolean isActive = zone == zones[i];
      ticks[i].setBackgroundResource(isActive ? active : clear);
      setViewColor(ticks[i], isActive ? urgencyColor : clearColor);
    }
  }

  private static void setViewColor(final View view, final int color) {
    if (view != null && view.getBackground() != null) {
      view.getBackground().setTint(color);
    }
  }

  private static String zoneWord(final NavigationGuidance.Zone zone) {
    switch (zone) {
      case LEFT:
        return "left";
      case RIGHT:
        return "right";
      default:
        return "ahead";
    }
  }

  private static String stateWord(final Urgency urgency) {
    switch (urgency) {
      case VERY_CLOSE:
        return "STOP";
      case CLOSE:
        return "CARE";
      default:
        return "CLEAR";
    }
  }

  /** Console "scan": a calm spoken summary of what Nino currently sees. */
  private void readScene() {
    final String summary = summarizeScene();
    if (summary == null) {
      return;
    }
    voiceNavigator.speakNow(summary);
    runOnUiThread(
        () -> {
          if (guidanceStatusView != null) {
            guidanceStatusView.setText(summary);
          }
          if (guidanceTickerView != null) {
            guidanceTickerView.setText(summary);
          }
        });
  }

  private String summarizeScene() {
    synchronized (lastFrameGuidance) {
      if (lastFrameGuidance.isEmpty()) {
        return null;
      }
      int people = 0;
      int others = 0;
      final StringBuilder sb = new StringBuilder("scene, ");
      for (final Guidance g : lastFrameGuidance) {
        final String t = g.getTitle() == null ? "object" : g.getTitle();
        if (t.toLowerCase().contains("person")) {
          people++;
        } else {
          others++;
        }
      }
      if (people > 0) {
        sb.append(people == 1 ? "one person" : people + " people");
      }
      if (people > 0 && others > 0) {
        sb.append(", ");
      }
      if (others > 0) {
        sb.append(others == 1 ? "one other object" : others + " other objects");
      }
      sb.append(", clear path ahead");
      return sb.toString();
    }
  }

  private void toggleVoice() {
    voiceNavigator.setMuted(!voiceNavigator.isMuted());
    runOnUiThread(this::updateVoiceButton);
  }

  private void updateVoiceButton() {
    if (dockVoiceButton == null) {
      return;
    }
    final boolean muted = voiceNavigator.isMuted();
    dockVoiceButton.setText(muted ? "muted" : getString(R.string.dock_voice));
    dockVoiceButton.setTextColor(
        muted ? Color.rgb(255, 95, 82) : getResources().getColor(R.color.fog, getTheme()));
  }

  private void updateRadar(final List<Recognition> mapped, final float w, final float h) {
    if (soundingChart == null || mapped == null) {
      return;
    }
    final List<Blip> blips = new ArrayList<>();
    for (final Recognition r : mapped) {
      final RectF loc = r.getLocation();
      if (loc == null) {
        continue;
      }
      final float cx = 1f - loc.centerX() / (w > 0 ? w : 1f);
      final float cy = loc.centerY() / (h > 0 ? h : 1f);
      final Guidance g = NavigationGuidance.evaluate(r, w, h);
      blips.add(new Blip(cx, cy, g.getUrgency()));
    }
    soundingChart.setBlips(blips);
  }

  @Override
  protected void processImage() {
    ++timestamp;
    final long currTimestamp = timestamp;
    trackingOverlay.postInvalidate();

    // No mutex needed as this method is not reentrant.
    if (computingDetection) {
      readyForNextImage();
      return;
    }
    computingDetection = true;
    LOGGER.i("Preparing image " + currTimestamp + " for detection in bg thread.");

    rgbFrameBitmap.setPixels(getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight);

    readyForNextImage();

    final Canvas canvas = new Canvas(croppedBitmap);
    canvas.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);
    // For examining the actual TF input.
    if (SAVE_PREVIEW_BITMAP) {
      ImageUtils.saveBitmap(croppedBitmap);
    }

    runInBackground(
        new Runnable() {
          @Override
          public void run() {
            LOGGER.i("Running detection on image " + currTimestamp);
            final long startTime = SystemClock.uptimeMillis();
            final List<Classifier.Recognition> results = detector.recognizeImage(croppedBitmap);
            lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime;

            cropCopyBitmap = Bitmap.createBitmap(croppedBitmap);
            final Canvas canvas = new Canvas(cropCopyBitmap);
            final Paint paint = new Paint();
            paint.setColor(Color.RED);
            paint.setStyle(Style.STROKE);
            paint.setStrokeWidth(2.0f);

            float minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
            switch (MODE) {
              case TF_OD_API:
                minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
                break;
            }

            final List<Classifier.Recognition> mappedRecognitions =
                new LinkedList<Classifier.Recognition>();

            for (final Classifier.Recognition result : results) {
              final RectF location = result.getLocation();
              if (location != null && result.getConfidence() >= minimumConfidence) {
                canvas.drawRect(location, paint);

                cropToFrameTransform.mapRect(location);

                result.setLocation(location);
                mappedRecognitions.add(result);
              }
            }

            // Turn the filtered detections into spoken navigation guidance.
            voiceNavigator.onNewDetections(mappedRecognitions, previewWidth, previewHeight);

            synchronized (lastFrameGuidance) {
              lastFrameGuidance.clear();
              for (final Recognition r : mappedRecognitions) {
                if (r.getLocation() == null) {
                  continue;
                }
                lastFrameGuidance.add(
                    NavigationGuidance.evaluate(r, previewWidth, previewHeight));
              }
            }

            tracker.trackResults(mappedRecognitions, currTimestamp);
            trackingOverlay.postInvalidate();

            computingDetection = false;

            runOnUiThread(
                new Runnable() {
                  @Override
                  public void run() {
                    updateRadar(mappedRecognitions, previewWidth, previewHeight);
                    showFrameInfo(previewWidth + "x" + previewHeight);
                    showCropInfo(cropCopyBitmap.getWidth() + "x" + cropCopyBitmap.getHeight());
                    showInference(lastProcessingTimeMs + "ms");
                  }
                });
          }
        });
  }

  @Override
  protected int getLayoutId() {
    return R.layout.camera_connection_fragment_tracking;
  }

  @Override
  protected Size getDesiredPreviewFrameSize() {
    return DESIRED_PREVIEW_SIZE;
  }

  // Which detection model to use: by default uses Tensorflow Object Detection API frozen
  // checkpoints.
  private enum DetectorMode {
    TF_OD_API;
  }

  @Override
  protected void setUseNNAPI(final boolean isChecked) {
    runInBackground(() -> detector.setUseNNAPI(isChecked));
  }

  @Override
  protected void setNumThreads(final int numThreads) {
    runInBackground(() -> detector.setNumThreads(numThreads));
  }

  @Override
  protected void onVoiceGuidanceToggled(final boolean enabled) {
    voiceNavigator.setMuted(!enabled);
    runOnUiThread(
        () -> {
          updateVoiceButton();
          if (guidanceStatusView != null) {
            guidanceStatusView.setText(
                enabled ? getString(R.string.guidance_waiting) : getString(R.string.guidance_muted));
          }
        });
  }
}
