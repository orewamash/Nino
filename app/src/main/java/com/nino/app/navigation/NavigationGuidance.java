/*
 * Nino - rule based guidance logic.
 *
 * This class is pure decision logic (no Android framework services). It turns a
 * detected object into:
 *   - a horizontal Zone  (Left / Center / Right)   -- which side of the screen
 *   - a rough Urgency     (Ahead / Close / Very Close) -- how near, from box size
 *   - a short spoken phrase combining label + zone + urgency
 *
 * Keeping the logic here, separate from TextToSpeech, makes it easy to explain
 * and test in isolation for the capstone viva.
 */

package com.nino.app.navigation;

import android.graphics.RectF;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.nino.app.tflite.Classifier.Recognition;

public final class NavigationGuidance {

  /** Horizontal screen zone: which third of the view the object is in. */
  public enum Zone {
    LEFT,
    CENTER,
    RIGHT
  }

  /** Rough closeness estimate, derived from the bounding box height. */
  public enum Urgency {
    AHEAD, // far away, low priority
    CLOSE, // mid distance
    VERY_CLOSE // imminent obstacle, highest priority
  }

  /** Bounding box taller than this fraction of the frame is treated as very close. */
  private static final float VERY_CLOSE_HEIGHT_FRACTION = 0.60f;

  /** Bounding box taller than this fraction of the frame is treated as close. */
  private static final float CLOSE_HEIGHT_FRACTION = 0.30f;

  /**
   * COCO labels that sound unnatural when read aloud verbatim are mapped to a
   * friendlier spoken form. Labels absent from this map are spoken as-is.
   */
  private static final Map<String, String> SPOKEN_LABELS = new HashMap<>();

  static {
    SPOKEN_LABELS.put("sports ball", "ball");
    SPOKEN_LABELS.put("hot dog", "hotdog");
    SPOKEN_LABELS.put("potted plant", "plant");
    SPOKEN_LABELS.put("cell phone", "phone");
    SPOKEN_LABELS.put("dining table", "table");
    SPOKEN_LABELS.put("hair drier", "hair dryer");
    SPOKEN_LABELS.put("tv", "television");
  }

  private NavigationGuidance() {}

  /**
   * Picks the single object worth announcing from a frame. When several objects
   * are visible we announce only the most relevant one (closest, then highest
   * confidence) so the user is not overwhelmed with a list.
   */
  public static Guidance selectMostRelevant(
      List<Recognition> detections, float frameWidth, float frameHeight) {
    Guidance best = null;
    for (Recognition recognition : detections) {
      if (recognition.getLocation() == null) {
        continue;
      }
      Guidance candidate = evaluate(recognition, frameWidth, frameHeight);
      if (best == null || candidate.compareTo(best) > 0) {
        best = candidate;
      }
    }
    return best;
  }

  /** Converts one detection into a Zone + Urgency + spoken label. */
  public static Guidance evaluate(Recognition recognition, float frameWidth, float frameHeight) {
    RectF location = recognition.getLocation();

    // The center of the box decides which horizontal third the object occupies.
    float centerX = location.centerX();
    Zone zone;
    if (centerX < frameWidth / 3.0f) {
      zone = Zone.LEFT;
    } else if (centerX > 2.0f * frameWidth / 3.0f) {
      zone = Zone.RIGHT;
    } else {
      zone = Zone.CENTER;
    }

    // A taller box means the object is nearer to the camera, hence more urgent.
    float heightFraction = frameHeight > 0 ? location.height() / frameHeight : 0.0f;
    Urgency urgency;
    if (heightFraction > VERY_CLOSE_HEIGHT_FRACTION) {
      urgency = Urgency.VERY_CLOSE;
    } else if (heightFraction > CLOSE_HEIGHT_FRACTION) {
      urgency = Urgency.CLOSE;
    } else {
      urgency = Urgency.AHEAD;
    }

    String rawLabel = recognition.getTitle();
    String title =
        rawLabel != null ? SPOKEN_LABELS.getOrDefault(rawLabel.toLowerCase(), rawLabel) : "";

    return new Guidance(
        title,
        zone,
        urgency,
        heightFraction,
        recognition.getConfidence() != null ? recognition.getConfidence() : 0.0f);
  }

  /**
   * Builds a short, natural spoken sentence from label + zone + urgency. Each
   * phrase is both descriptive AND directive: it names the object and tells the
   * user which action keeps them safe, e.g.
   *   "person ahead"
   *   "person close ahead, slow down"
   *   "chair on your left, move right"
   *   "person very close ahead, stop"
   * Sentences are kept to a few words because they are spoken repeatedly.
   */
  public static String buildPhrase(String label, Zone zone, Urgency urgency) {
    StringBuilder phrase = new StringBuilder(label);

    if (urgency == Urgency.VERY_CLOSE) {
      phrase.append(" very close");
    } else if (urgency == Urgency.CLOSE) {
      phrase.append(" close");
    }

    switch (zone) {
      case LEFT:
        phrase.append(" on your left");
        break;
      case RIGHT:
        phrase.append(" on your right");
        break;
      default:
        phrase.append(" ahead");
        break;
    }

    // Directive part: the action that keeps the user safe.
    if (urgency == Urgency.VERY_CLOSE) {
      phrase.append(", stop");
    } else if (urgency == Urgency.CLOSE) {
      if (zone == Zone.CENTER) {
        phrase.append(", slow down");
      } else {
        phrase.append(", step ").append(zone == Zone.LEFT ? "right" : "left");
      }
    } else if (zone == Zone.LEFT) {
      phrase.append(", move right");
    } else if (zone == Zone.RIGHT) {
      phrase.append(", move left");
    }

    return phrase.toString();
  }

  /** Immutable result of evaluating one detection. */
  public static final class Guidance implements Comparable<Guidance> {
    private final String title;
    private final Zone zone;
    private final Urgency urgency;
    private final float heightFraction;
    private final float confidence;

    Guidance(String title, Zone zone, Urgency urgency, float heightFraction, float confidence) {
      this.title = title;
      this.zone = zone;
      this.urgency = urgency;
      this.heightFraction = heightFraction;
      this.confidence = confidence;
    }

    public String getTitle() {
      return title;
    }

    public Zone getZone() {
      return zone;
    }

    public Urgency getUrgency() {
      return urgency;
    }

    public float getHeightFraction() {
      return heightFraction;
    }

    public float getConfidence() {
      return confidence;
    }

    /**
     * Ordering used to pick which object to announce: most urgent first, ties
     * broken by detection confidence. A higher value means "more important".
     */
    @Override
    public int compareTo(Guidance other) {
      int urgencyOrder = Integer.compare(urgency.ordinal(), other.urgency.ordinal());
      if (urgencyOrder != 0) {
        return urgencyOrder;
      }
      return Float.compare(confidence, other.confidence);
    }
  }
}
