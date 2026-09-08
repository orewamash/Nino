/* Nino - sounding-chart radar. */

package com.nino.app.customview;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import com.nino.app.navigation.NavigationGuidance.Urgency;
import java.util.ArrayList;
import java.util.List;

/**
 * A small sounding-chart radar, mirroring the mock HUD. Draws concentric depth
 * rings, a rotating sweep (the "still scanning" cue) and detection blips
 * colored by urgency. Rings/axes are a genuine chart (map) surface, matching
 * the mock world's chart-room idea.
 */
public class SoundingChartView extends View {

  /** A single detection blip in normalized (0..1) chart space. */
  public static final class Blip {
    public final float x;
    public final float y;
    public final Urgency urgency;

    public Blip(final float x, final float y, final Urgency urgency) {
      this.x = x;
      this.y = y;
      this.urgency = urgency;
    }
  }

  private static final int FOG = Color.rgb(157, 178, 192);
  private static final int FOG_FAINT = Color.rgb(90, 107, 122);
  private static final int TEAL = Color.rgb(64, 224, 194);
  private static final int AMBER = Color.rgb(255, 182, 75);
  private static final int CORAL = Color.rgb(255, 95, 82);

  private final Paint ringPaint = new Paint();
  private final Paint axisPaint = new Paint();
  private final Paint sweepPaint = new Paint();
  private final Paint blipPaint = new Paint();
  private final Paint blipGlowPaint = new Paint();
  private final float density;

  private volatile float sweepAngle = 0f;
  private final List<Blip> blips = new ArrayList<>();

  public SoundingChartView(final Context context, final AttributeSet attrs) {
    super(context, attrs);
    density = getResources().getDisplayMetrics().density;

    ringPaint.setColor(FOG_FAINT);
    ringPaint.setStyle(Paint.Style.STROKE);
    ringPaint.setStrokeWidth(1f);
    ringPaint.setAntiAlias(true);

    axisPaint.setColor(FOG_FAINT);
    axisPaint.setStrokeWidth(1f);

    sweepPaint.setColor(TEAL);
    sweepPaint.setStyle(Paint.Style.STROKE);
    sweepPaint.setStrokeWidth(2f);
    sweepPaint.setAlpha(190);
    sweepPaint.setAntiAlias(true);

    blipGlowPaint.setStyle(Paint.Style.STROKE);
    blipGlowPaint.setStrokeWidth(3f);
    blipGlowPaint.setAntiAlias(true);
  }

  /** Feed the current frame's blips; safest called on the UI thread. */
  public void setBlips(final List<Blip> newBlips) {
    blips.clear();
    blips.addAll(newBlips);
    postInvalidate();
  }

  /** Advance the sweep by the given degrees (call from a ValueAnimator). */
  public void setSweepAngle(final float angle) {
    sweepAngle = angle;
    postInvalidate();
  }

  @Override
  protected void onDraw(final Canvas canvas) {
    final float cx = getWidth() / 2f;
    final float cy = getHeight() / 2f;
    final float max = Math.min(getWidth(), getHeight());
    final float r = max / 2f - 6f * density;

    // Depth rings.
    for (int i = 1; i <= 3; i++) {
      canvas.drawCircle(cx, cy, r * i / 3f, ringPaint);
    }
    // Cross axes.
    canvas.drawLine(cx, cy - r, cx, cy + r, axisPaint);
    canvas.drawLine(cx - r, cy, cx + r, cy, axisPaint);

    // Sweep wedge: a soft trailing arc + leading line, the "still scanning" cue.
    final float angle = sweepAngle * (float) (Math.PI / 180.0);
    for (int i = 0; i < 8; i++) {
      final float a = angle - i * 0.06f;
      sweepPaint.setAlpha(Math.max(20, 190 - i * 26));
      canvas.drawLine(
          cx,
          cy,
          cx + (float) Math.sin(a) * r,
          cy - (float) Math.cos(a) * r,
          sweepPaint);
    }

    // Blips colored by urgency.
    for (final Blip blip : blips) {
      final float px = cx + (blip.x - 0.5f) * 2f * r;
      final float py = cy - (blip.y - 0.5f) * 2f * r;
      final int color = urgencyColor(blip.urgency);
      blipGlowPaint.setColor(color);
      blipGlowPaint.setAlpha(120);
      canvas.drawCircle(px, py, 5f * density, blipGlowPaint);
      blipPaint.setColor(color);
      canvas.drawCircle(px, py, 2.5f * density, blipPaint);
    }
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
}