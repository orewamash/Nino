/* Nino - Navigation Assistant for the Visually Impaired. */

package com.nino.app.customview;

import java.util.List;
import com.nino.app.tflite.Classifier.Recognition;

public interface ResultsView {
  public void setResults(final List<Recognition> results);
}
