/* Nino - Navigation Assistant for the Visually Impaired. */

package com.nino.app.tracking;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Cap;
import android.graphics.Paint.Join;
import android.graphics.Paint.Style;
import android.graphics.RectF;
import android.text.TextUtils;
import android.util.TypedValue;
import java.util.LinkedList;
import java.util.List;
import com.nino.app.env.BorderedText;
import com.nino.app.env.ImageUtils;
import com.nino.app.env.Logger;
import com.nino.app.navigation.NavigationGuidance.Urgency;
import com.nino.app.tflite.Classifier.Recognition;

/**
 * A tracker that handles non-max suppression and matches existing objects to new detections.
 * Draws detections as Harbor Beacon buoys: corner ticks + an urgency light, no heavy box.
 */
public class MultiBoxTracker {
  private static final float TEXT_SIZE_DIP = 14;
  private static final float MIN_SIZE = 16.0f;
  private static final int TEAL = Color.rgb(64, 224, 194);
  private static final int AMBER = Color.rgb(255, 182, 75);
  private static final int CORAL = Color.rgb(255, 95, 82);

  // Same thresholds as NavigationGuidance: box height fraction -> urgency.
  private static final float VERY_CLOSE_HEIGHT_FRACTION = 0.60f;
  private static final float CLOSE_HEIGHT_FRACTION = 0.30f;

  final List<RectF> screenRects = new LinkedList<RectF>();
  private final Logger logger = new Logger();
  private final List<TrackedRecognition> trackedObjects = new LinkedList<TrackedRecognition>();
  private final Paint boxPaint = new Paint();
  private final Paint focusPaint = new Paint();
  private final Paint tokenPaint = new Paint();
  private final float textSizePx;
  private final BorderedText borderedText;
  private Matrix frameToCanvasMatrix;
  private int frameWidth;
  private int frameHeight;
  private int sensorOrientation;

  /** Title of the buoy currently being spoken about, if any. */
  private String focusTitle;

  public MultiBoxTracker(final Context context) {
    boxPaint.setStrokeWidth(3.0f);
    boxPaint.setStrokeCap(Cap.ROUND);
    boxPaint.setStrokeJoin(Join.ROUND);

    focusPaint.setStrokeWidth(5.0f);
    focusPaint.setStrokeCap(Cap.ROUND);
    focusPaint.setStrokeJoin(Join.ROUND);

    tokenPaint.setStyle(Style.FILL);
    tokenPaint.setAntiAlias(true);

    textSizePx =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, context.getResources().getDisplayMetrics());
    borderedText = new BorderedText(textSizePx);
  }

  public synchronized void setFrameConfiguration(
      final int width, final int height, final int sensorOrientation) {
    frameWidth = width;
    frameHeight = height;
    this.sensorOrientation = sensorOrientation;
  }

  /** Marks the buoy that is currently being spoken about for a brighter render. */
  public synchronized void setFocusTitle(final String title) {
    focusTitle = title;
  }

  public synchronized void drawDebug(final Canvas canvas) {
    final Paint textPaint = new Paint();
    textPaint.setColor(Color.WHITE);
    textPaint.setTextSize(60.0f);

    final Paint boxPaint = new Paint();
    boxPaint.setColor(Color.RED);
    boxPaint.setAlpha(200);
    boxPaint.setStyle(Style.STROKE);

    for (final RectF rect : screenRects) {
      canvas.drawRect(rect, boxPaint);
      canvas.drawText("rect", rect.left, rect.top, textPaint);
    }
  }

  public synchronized void trackResults(final List<Recognition> results, final long timestamp) {
    logger.i("Processing %d results from %d", results.size(), timestamp);
    processResults(results);
  }

  private Matrix getFrameToCanvasMatrix() {
    return frameToCanvasMatrix;
  }

  public synchronized void draw(final Canvas canvas) {
    final boolean rotated = sensorOrientation % 180 == 90;
    final float multiplier =
        Math.min(
            canvas.getHeight() / (float) (rotated ? frameWidth : frameHeight),
            canvas.getWidth() / (float) (rotated ? frameHeight : frameWidth));
    frameToCanvasMatrix =
        ImageUtils.getTransformationMatrix(
            frameWidth,
            frameHeight,
            (int) (multiplier * (rotated ? frameHeight : frameWidth)),
            (int) (multiplier * (rotated ? frameWidth : frameHeight)),
            sensorOrientation,
            false);

    for (final TrackedRecognition recognition : trackedObjects) {
      final RectF trackedPos = new RectF(recognition.location);
      getFrameToCanvasMatrix().mapRect(trackedPos);

      final Urgency urgency = urgencyOf(recognition.heightFraction);
      final int color = urgencyColor(urgency);
      final boolean focused = recognition.title != null && recognition.title.equals(focusTitle);

      drawBuoy(canvas, trackedPos, color, focused);

      final String labelString =
          !TextUtils.isEmpty(recognition.title)
              ? String.format("%s %.0f%%", recognition.title, (100 * recognition.detectionConfidence))
              : String.format("%.0f%%", (100 * recognition.detectionConfidence));
      borderedText.setInteriorColor(color);
      borderedText.drawText(canvas, trackedPos.left, trackedPos.bottom + 2.0f, labelString);
    }
  }

  /** Corner bracket ticks + an urgency dot, a buoy rather than a box. */
  private void drawBuoy(final Canvas canvas, final RectF r, final int color, final boolean focused) {
    final float t = Math.min(r.width(), r.height()) / 7.0f;
    boxPaint.setColor(color);
    boxPaint.setStyle(Style.STROKE);
    boxPaint.setAlpha(focused ? 255 : 190);
    canvas.drawLine(r.left, r.top + t, r.left, r.top, boxPaint);
    canvas.drawLine(r.left, r.top, r.left + t, r.top, boxPaint);
    canvas.drawLine(r.right - t, r.top, r.right, r.top, boxPaint);
    canvas.drawLine(r.right, r.top, r.right, r.top + t, boxPaint);
    canvas.drawLine(r.right, r.bottom - t, r.right, r.bottom, boxPaint);
    canvas.drawLine(r.right, r.bottom, r.right - t, r.bottom, boxPaint);
    canvas.drawLine(r.left + t, r.bottom, r.left, r.bottom, boxPaint);
    canvas.drawLine(r.left, r.bottom, r.left, r.bottom - t, boxPaint);

    // Focus lock: a bright ring + token so sighted viewers know which buoy Nino is speaking about.
    if (focused) {
      focusPaint.setColor(color);
      focusPaint.setAlpha(200);
      canvas.drawRoundRect(r, 6.0f, 6.0f, focusPaint);
      tokenPaint.setColor(color);
      canvas.drawCircle(r.centerX(), r.top, 5.0f, tokenPaint);
    } else {
      // Urgency light at the top-center of the buoy.
      tokenPaint.setColor(color);
      tokenPaint.setAlpha(140);
      canvas.drawCircle(r.centerX(), r.top, 3.0f, tokenPaint);
    }
  }

  private static Urgency urgencyOf(final float heightFraction) {
    if (heightFraction > VERY_CLOSE_HEIGHT_FRACTION) {
      return Urgency.VERY_CLOSE;
    }
    if (heightFraction > CLOSE_HEIGHT_FRACTION) {
      return Urgency.CLOSE;
    }
    return Urgency.AHEAD;
  }

  private static int urgencyColor(final Urgency urgency) {
    switch (urgency) {
      case VERY_CLOSE:
        return CORAL;
      case CLOSE:
        return AMBER;
      default:
        return TEAL;
    }
  }

  private void processResults(final List<Recognition> results) {
    final List<Recognition> rectsToTrack = new LinkedList<Recognition>();

    // The frame-to-canvas matrix is only built on the first draw() call. If a
    // trackResults() arrives first (e.g. first frame), bail out gracefully
    // instead of crashing on new Matrix(null).
    final Matrix frameToScreen = getFrameToCanvasMatrix();
    if (frameToScreen == null) {
      return;
    }
    screenRects.clear();
    final Matrix rgbFrameToScreen = new Matrix(frameToScreen);

    for (final Recognition result : results) {
      if (result.getLocation() == null) {
        continue;
      }
      final RectF detectionFrameRect = new RectF(result.getLocation());

      final RectF detectionScreenRect = new RectF();
      rgbFrameToScreen.mapRect(detectionScreenRect, detectionFrameRect);

      screenRects.add(detectionScreenRect);

      if (detectionFrameRect.width() < MIN_SIZE || detectionFrameRect.height() < MIN_SIZE) {
        logger.w("Degenerate rectangle! " + detectionFrameRect);
        continue;
      }

      result.setLocation(detectionScreenRect);
      rectsToTrack.add(result);
    }

    trackedObjects.clear();
    if (rectsToTrack.isEmpty()) {
      logger.v("Nothing to track, aborting.");
      return;
    }

    for (final Recognition result : rectsToTrack) {
      final RectF loc = result.getLocation();
      if (loc == null) {
        continue;
      }
      final TrackedRecognition trackedRecognition = new TrackedRecognition();
      trackedRecognition.detectionConfidence =
          result.getConfidence() != null ? result.getConfidence() : 0.0f;
      trackedRecognition.location = new RectF(loc);
      trackedRecognition.title = result.getTitle();
      trackedRecognition.heightFraction =
          frameHeight > 0 ? loc.height() / (float) frameHeight : 0.0f;
      trackedObjects.add(trackedRecognition);
    }
  }

  private static class TrackedRecognition {
    RectF location;
    float detectionConfidence;
    String title;
    float heightFraction;
  }
}