/*
 * Copyright 2016 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tensorflow.demo;

import android.graphics.*;
import android.graphics.Bitmap.Config;
import android.media.Image;
import android.media.Image.Plane;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.SystemClock;
import android.os.Trace;
import android.util.Size;
import android.util.TypedValue;
import android.view.Display;
import java.util.List;
import java.util.Vector;
import org.tensorflow.demo.OverlayView.DrawCallback;
import org.tensorflow.demo.env.BorderedText;
import org.tensorflow.demo.env.ImageUtils;
import org.tensorflow.demo.env.Logger;
import org.tensorflow.demo.env.RectUtils;

public class NumSeqClassifierActivity extends CameraActivity implements OnImageAvailableListener {
    private static final Logger LOGGER = new Logger();

    private static final boolean SAVE_PREVIEW_BITMAP = false;
    private static final boolean MAINTAIN_ASPECT = true;

    private NumSeqImageClassifier classifier;
    private BboxImageDetector bboxDetector;

    private Integer sensorOrientation;

    private int inputSize = BboxImageDetector.INPUT_SIZE;
    private int previewWidth = 0;
    private int previewHeight = 0;
    private byte[][] yuvBytes;
    private int[] rgbBytes = null;
    private Bitmap rgbFrameBitmap = null;
    private Bitmap bboxBitmap = null;
    private Rect inferedBbox = null;
    private Bitmap croppedBitmap = null;

    private Bitmap cropCopyBitmap;

    private boolean computing = false;


    private Matrix frameToCropTransform;
    private Matrix cropToFrameTransform;

    private ResultsView resultsView;

    private BorderedText borderedText;

    private long lastProcessingTimeMs;

    @Override
    protected int getLayoutId() {
        return R.layout.camera_connection_fragment;
    }

    @Override
    protected int getDesiredPreviewFrameSize() {
        return inputSize;
    }

    private static final float TEXT_SIZE_DIP = 10;

    @Override
    public void onPreviewSizeChosen(final Size size, final int rotation) {
        final float textSizePx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP,
                getResources().getDisplayMetrics());
        borderedText = new BorderedText(textSizePx);
        borderedText.setTypeface(Typeface.MONOSPACE);

        bboxDetector = new BboxImageDetector();
        try {
            bboxDetector.initializeTensorFlow(getAssets());
        } catch (final Exception e) {
            throw new RuntimeException("Error initializing TensorFlow!", e);
        }

        classifier = new NumSeqImageClassifier();

        try {
            classifier.initializeTensorFlow(getAssets());
        } catch (final Exception e) {
            throw new RuntimeException("Error initializing TensorFlow!", e);
        }

        resultsView = (ResultsView) findViewById(R.id.results);
        previewWidth = size.getWidth();
        previewHeight = size.getHeight();

        final Display display = getWindowManager().getDefaultDisplay();
        final int screenOrientation = display.getRotation();

        LOGGER.i("Sensor orientation: %d, Screen orientation: %d",
                rotation, screenOrientation);

        sensorOrientation = rotation + screenOrientation;

        LOGGER.i("Initializing at size %dx%d", previewWidth, previewHeight);
        rgbBytes = new int[previewWidth * previewHeight];
        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888);
        bboxBitmap = Bitmap.createBitmap(inputSize, inputSize, Config.ARGB_8888);
        croppedBitmap = Bitmap.createBitmap(NumSeqImageClassifier.INPUT_SIZE, NumSeqImageClassifier.INPUT_SIZE, Config.ARGB_8888);

        frameToCropTransform = ImageUtils.getTransformationMatrix(
                previewWidth, previewHeight,
                inputSize, inputSize,
                sensorOrientation, MAINTAIN_ASPECT);

        cropToFrameTransform = new Matrix();
        frameToCropTransform.invert(cropToFrameTransform);

        yuvBytes = new byte[3][];

        addCallback(new DrawCallback() {
            @Override
            public void drawCallback(final Canvas canvas) {
                renderDebug(canvas);
            }
        });
    }

    @Override
    public void onImageAvailable(final ImageReader reader) {
        Image image = null;

        try {
            image = reader.acquireLatestImage();

            if (image == null) {
                return;
            }

            if (computing) {
                image.close();
                return;
            }
            computing = true;

            Trace.beginSection("imageAvailable");

            final Plane[] planes = image.getPlanes();
            fillBytes(planes, yuvBytes);

            final int yRowStride = planes[0].getRowStride();
            final int uvRowStride = planes[1].getRowStride();
            final int uvPixelStride = planes[1].getPixelStride();
            ImageUtils.convertYUV420ToARGB8888(
                    yuvBytes[0],
                    yuvBytes[1],
                    yuvBytes[2],
                    rgbBytes,
                    previewWidth,
                    previewHeight,
                    yRowStride,
                    uvRowStride,
                    uvPixelStride,
                    false);

            image.close();
        } catch (final Exception e) {
            if (image != null) {
                image.close();
            }
            LOGGER.e(e, "Exception!");
            Trace.endSection();
            return;
        }

        rgbFrameBitmap.setPixels(rgbBytes, 0, previewWidth, 0, 0, previewWidth, previewHeight);
        final Canvas bboxCanvas = new Canvas(bboxBitmap);
        bboxCanvas.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);

        // For examining the actual TF input.
        if (SAVE_PREVIEW_BITMAP) {
            ImageUtils.saveBitmap(bboxBitmap);
        }

        runInBackground(
                new Runnable() {
                    @Override
                    public void run() {
                        final long startTime = SystemClock.uptimeMillis();
                        final List<Classifier.Recognition> bboxes = bboxDetector.recognizeImage(bboxBitmap);

                        final Canvas canvas = new Canvas(croppedBitmap);
                        inferedBbox = RectUtils.fixRect(inputSize, inputSize, bboxes.get(0).getLocation());
                        canvas.drawBitmap(bboxBitmap, inferedBbox, inferedBbox, null);

                        final List<Classifier.Recognition> results = classifier.recognizeImage(croppedBitmap);
                        results.addAll(0, bboxes);
                        lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime;

                        cropCopyBitmap = Bitmap.createBitmap(croppedBitmap);
                        resultsView.setResults(results);
                        requestRender();
                        computing = false;
                    }
                });

        Trace.endSection();
    }

    @Override
    public void onSetDebug(boolean debug) {
        classifier.enableStatLogging(debug);
    }

    private void renderDebug(final Canvas canvas) {
        if (!isDebug()) {
            return;
        }
        final Bitmap copy = cropCopyBitmap;
        if (copy != null) {
            final Matrix matrix = new Matrix();
            final float scaleFactor = 2;
            matrix.postScale(scaleFactor, scaleFactor);
            matrix.postTranslate(
                    canvas.getWidth() - copy.getWidth() * scaleFactor,
                    canvas.getHeight() - copy.getHeight() * scaleFactor);
            canvas.drawBitmap(copy, matrix, new Paint());

            final Vector<String> lines = new Vector<String>();
            if (classifier != null) {
                String statString = classifier.getStatString();
                String[] statLines = statString.split("\n");
                for (String line : statLines) {
                    lines.add(line);
                }
            }

            lines.add("Frame: " + previewWidth + "x" + previewHeight);
            lines.add(String.format("Infered bbox: %sx%s-%sx%s", inferedBbox.left, inferedBbox.top, inferedBbox.right, inferedBbox.bottom));
            lines.add("Crop: " + copy.getWidth() + "x" + copy.getHeight());
            lines.add("View: " + canvas.getWidth() + "x" + canvas.getHeight());
            lines.add("Rotation: " + sensorOrientation);
            lines.add("Inference time: " + lastProcessingTimeMs + "ms");

            borderedText.drawLines(canvas, 10, canvas.getHeight() - 10, lines);
        }
    }
}
