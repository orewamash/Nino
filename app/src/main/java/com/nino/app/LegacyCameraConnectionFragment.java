package com.nino.app;

/* Nino - Navigation Assistant for the Visually Impaired. */

import android.app.Activity;
import android.app.Fragment;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import java.io.IOException;
import java.util.List;
import com.nino.app.customview.AutoFitTextureView;
import com.nino.app.env.ImageUtils;
import com.nino.app.env.Logger;

public class LegacyCameraConnectionFragment extends Fragment {
  private static final Logger LOGGER = new Logger();
  /** Conversion from screen rotation to JPEG orientation. */
  private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

  static {
    ORIENTATIONS.append(Surface.ROTATION_0, 90);
    ORIENTATIONS.append(Surface.ROTATION_90, 0);
    ORIENTATIONS.append(Surface.ROTATION_180, 270);
    ORIENTATIONS.append(Surface.ROTATION_270, 180);
  }

  private Camera camera;
  private Camera.PreviewCallback imageListener;
  private Size desiredSize;
  /** The layout identifier to inflate for this Fragment. */
  private int layout;
  /** An {@link AutoFitTextureView} for camera preview. */
  private AutoFitTextureView textureView;
  /**
   * {@link TextureView.SurfaceTextureListener} handles several lifecycle events on a {@link
   * TextureView}.
   */
  private final TextureView.SurfaceTextureListener surfaceTextureListener =
      new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(
            final SurfaceTexture texture, final int width, final int height) {
          openPreview(texture);
        }

        @Override
        public void onSurfaceTextureSizeChanged(
            final SurfaceTexture texture, final int width, final int height) {}

        @Override
        public boolean onSurfaceTextureDestroyed(final SurfaceTexture texture) {
          return true;
        }

        @Override
        public void onSurfaceTextureUpdated(final SurfaceTexture texture) {}
      };
  /** An additional thread for running tasks that shouldn't block the UI. */
  private HandlerThread backgroundThread;

  public LegacyCameraConnectionFragment(
      final Camera.PreviewCallback imageListener, final int layout, final Size desiredSize) {
    this.imageListener = imageListener;
    this.layout = layout;
    this.desiredSize = desiredSize;
  }

  @Override
  public View onCreateView(
      final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {
    return inflater.inflate(layout, container, false);
  }

  @Override
  public void onViewCreated(final View view, final Bundle savedInstanceState) {
    textureView = (AutoFitTextureView) view.findViewById(R.id.texture);
  }

  @Override
  public void onActivityCreated(final Bundle savedInstanceState) {
    super.onActivityCreated(savedInstanceState);
  }

  @Override
  public void onResume() {
    super.onResume();
    startBackgroundThread();
    // When the screen is turned off and turned back on, the SurfaceTexture is already
    // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
    // a camera and start preview from here (otherwise, we wait until the surface is ready in
    // the SurfaceTextureListener).
    if (textureView.isAvailable()) {
      if (camera != null) {
        camera.startPreview();
      } else {
        // onPause() closed the camera but the surface is still available; reopening here
        // instead of calling camera.startPreview() avoids a NullPointerException on resume.
        openPreview(textureView.getSurfaceTexture());
      }
    } else {
      textureView.setSurfaceTextureListener(surfaceTextureListener);
    }
  }

  @Override
  public void onPause() {
    stopCamera();
    stopBackgroundThread();
    super.onPause();
  }

  /** Starts a background thread and its {@link Handler}. */
  private void startBackgroundThread() {
    backgroundThread = new HandlerThread("CameraBackground");
    backgroundThread.start();
  }

  /** Stops the background thread and its {@link Handler}. */
  private void stopBackgroundThread() {
    backgroundThread.quitSafely();
    try {
      backgroundThread.join();
      backgroundThread = null;
    } catch (final InterruptedException e) {
      LOGGER.e(e, "Exception!");
    }
  }

  protected void stopCamera() {
    if (camera != null) {
      camera.stopPreview();
      camera.setPreviewCallback(null);
      camera.release();
      camera = null;
    }
  }

  /** Opens the back camera and starts the preview on the given {@link SurfaceTexture}. */
  private void openPreview(final SurfaceTexture texture) {
    final int index = getCameraId();
    if (index < 0) {
      // No back-facing camera available; bail out gracefully instead of crashing.
      LOGGER.e(new RuntimeException(), "No back-facing camera found.");
      showCameraErrorAndFinish();
      return;
    }

    try {
      camera = Camera.open(index);
    } catch (final RuntimeException e) {
      // Thrown when the camera is in use by another app or otherwise unavailable.
      LOGGER.e(e, "Exception opening camera!");
      camera = null;
      showCameraErrorAndFinish();
      return;
    }

    try {
      Camera.Parameters parameters = camera.getParameters();
      List<String> focusModes = parameters.getSupportedFocusModes();
      if (focusModes != null
          && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
        parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
      }
      List<Camera.Size> cameraSizes = parameters.getSupportedPreviewSizes();
      Size[] sizes = new Size[cameraSizes.size()];
      int i = 0;
      for (Camera.Size size : cameraSizes) {
        sizes[i++] = new Size(size.width, size.height);
      }
      Size previewSize =
          CameraConnectionFragment.chooseOptimalSize(
              sizes, desiredSize.getWidth(), desiredSize.getHeight());
      parameters.setPreviewSize(previewSize.getWidth(), previewSize.getHeight());
      camera.setDisplayOrientation(90);
      camera.setParameters(parameters);
      camera.setPreviewTexture(texture);
    } catch (final IOException | RuntimeException e) {
      LOGGER.e(e, "Exception setting up camera preview!");
      if (camera != null) {
        camera.release();
        camera = null;
      }
      showCameraErrorAndFinish();
      return;
    }

    camera.setPreviewCallbackWithBuffer(imageListener);
    Camera.Size s = camera.getParameters().getPreviewSize();
    camera.addCallbackBuffer(new byte[ImageUtils.getYUVByteSize(s.height, s.width)]);

    textureView.setAspectRatio(s.height, s.width);

    camera.startPreview();
  }

  /** Shows a short error message and closes the activity so it never sits on a black screen. */
  private void showCameraErrorAndFinish() {
    final Activity activity = getActivity();
    if (activity != null) {
      activity.runOnUiThread(
          new Runnable() {
            @Override
            public void run() {
              Toast.makeText(activity, R.string.camera_error, Toast.LENGTH_SHORT).show();
              activity.finish();
            }
          });
    }
  }

  private int getCameraId() {
    CameraInfo ci = new CameraInfo();
    for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
      Camera.getCameraInfo(i, ci);
      if (ci.facing == CameraInfo.CAMERA_FACING_BACK) return i;
    }
    return -1; // No camera found
  }
}
